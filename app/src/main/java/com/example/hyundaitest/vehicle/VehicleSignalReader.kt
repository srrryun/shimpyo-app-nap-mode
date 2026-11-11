package com.example.hyundaitest.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 쉼표 시그널: 휴식 전·깨어난 뒤 보여줄 차량 상태 (설계서 3장, 5장 ④).
 * 읽지 못한 값은 null → 화면에 "확인 불가". 확인할 수 없는 항목을 정상이라고 표시하지 않는다.
 */
data class VehicleSignal(
    val isVehicleConnected: Boolean,
    val batteryPercent: Int?,
    /** true = 모든 문 잠김, false = 하나라도 열림, null = 확인 불가 */
    val allDoorsLocked: Boolean?,
    val hvacPowerOn: Boolean?,
    val hvacTemperatureCelsius: Float?,
) {
    /** 한 줄 요약. 예) 배터리 72% · 문 잠김 · 공조 켜짐 24.0℃ */
    fun summary(): String {
        if (!isVehicleConnected) return "차량에 연결되지 않았어요 (차량 상태 확인 불가)"
        val battery = batteryPercent?.let { "배터리 $it%" } ?: "배터리 확인 불가"
        val doors = when (allDoorsLocked) {
            true -> "문 잠김"
            false -> "문 열림"
            null -> "문 잠금 확인 불가"
        }
        val climate = when (hvacPowerOn) {
            true -> "공조 켜짐" + (hvacTemperatureCelsius?.let { " ${"%.1f".format(it)}℃" } ?: "")
            false -> "공조 꺼짐"
            null -> "공조 확인 불가"
        }
        return "$battery · $doors · $climate"
    }

    companion object {
        val NOT_CONNECTED = VehicleSignal(false, null, null, null, null)
    }
}

/** 차량 상태를 읽는다 (읽기만, 제어 없음) */
class VehicleSignalReader(private val vehicleProvider: () -> Vehicle?) {

    suspend fun readSignal(): VehicleSignal = coroutineScope {
        val vehicle = vehicleProvider() ?: return@coroutineScope VehicleSignal.NOT_CONNECTED
        val battery = async { readBattery(vehicle) }
        val doors = async { readAllDoorsLocked(vehicle) }
        val power = async { readHvacPower(vehicle) }
        val temperature = async { readTemperature(vehicle) }
        VehicleSignal(true, battery.await(), doors.await(), power.await(), temperature.await())
    }

    private suspend fun readBattery(vehicle: Vehicle): Int? =
        awaitPleos<Int?> { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }.valueOrNull()

    private suspend fun readAllDoorsLocked(vehicle: Vehicle): Boolean? {
        val areas = awaitPleos<Pair<CapabilityConfig, List<DoorArea>>> { ok, fail ->
            vehicle.getDoor().checkDoorLockCapabilities(onComplete = { c, l -> ok(c to l) }, onFailed = fail)
        }.valueOrNull()?.second
            ?.filter { DoorValue.HOOD !in it.values && DoorValue.REAR !in it.values }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val states = areas.map { area ->
            awaitPleos<Boolean?> { ok, fail -> vehicle.getDoor().getDoorLockState(area, ok, fail) }.valueOrNull()
        }
        return when {
            states.any { it == false } -> false
            states.all { it == true } -> true
            else -> null
        }
    }

    private suspend fun readHvacPower(vehicle: Vehicle): Boolean? =
        awaitPleos<Boolean?> { ok, fail -> vehicle.getHVAC().getHvacPowerState(DRIVER, ok, fail) }.valueOrNull()

    private suspend fun readTemperature(vehicle: Vehicle): Float? =
        awaitPleos<Float?> { ok, fail ->
            vehicle.getHVAC().getHvacTemperature(DRIVER, TemperatureUnit.CELSIUS, ok, fail)
        }.valueOrNull()

    private companion object {
        val DRIVER = SeatArea(SeatValue.ROW_1_LEFT)
    }
}
