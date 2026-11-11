package com.example.hyundaitest.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.Context
import android.util.Log
import com.example.hyundaitest.safety.ParkingReading
import com.example.hyundaitest.safety.ParkingSourceResult
import com.example.hyundaitest.safety.ParkingSourceResult.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.resume

/**
 * 차가 주차 상태인지 읽어 온다 (설계서 8장 S-01, 15장 D-6).
 * 차량 상태를 바꾸지 않고 읽기만 한다.
 */
interface ParkingStatusReader {
    suspend fun readParkingStatus(): ParkingReading
}

/** 차량 없이 돌릴 때 (단위 테스트 등). 항상 "자동 확인 불가" */
class NoVehicleParkingStatusReader : ParkingStatusReader {
    override suspend fun readParkingStatus(): ParkingReading = ParkingReading.UNKNOWN
}

/**
 * 읽을 수 있는 곳을 모두 동시에 시도하고, 결과를 ParkingReading.fromSources 규칙으로 합친다.
 * 어느 곳이 실제로 되는지는 에뮬레이터(차량 기능 점검 화면)에서 확인한다.
 *
 *  1. 파킹브레이크 · 안드로이드 표준 (PARKING_BRAKE_ON)      권한 android.car.permission.CAR_POWERTRAIN
 *  2. 기어 P단    · 안드로이드 표준 (GEAR_SELECTION)        권한 android.car.permission.CAR_POWERTRAIN
 *     → Pleos SDK가 기어 API 대신 쓰라고 안내한 값
 *  3. 기어 P단    · Pleos Gear (예전 방식, deprecated)      권한 pleos CAR_DRIVE
 *
 * 에뮬레이터(2026-11-09) 결과: 세 곳 모두 읽기 성공.
 * Pleos 경고등(Telltale)으로 파킹브레이크를 읽는 방법은 빈 목록만 오고 제조사 전용 권한
 * (PRIVATE_CAR_SAFETY)이 필요해서 뺐다. 원본은 _backup_2026-11-09_parking_check/ 에 있다.
 *
 * 하나라도 실패해도 나머지로 판단한다. 모두 실패하면 UNKNOWN.
 */
class VehicleParkingStatusReader(
    context: Context,
    private val vehicleProvider: () -> Vehicle?,
    private val timeoutMillis: Long = 3_000L,
) : ParkingStatusReader {

    private val appContext = context.applicationContext

    override suspend fun readParkingStatus(): ParkingReading = coroutineScope {
        val vehicle = vehicleProvider()
        val sources = listOf(
            async { readStandardParkingBrake() },
            async { readStandardGearInPark() },
            async { readPleosGearInPark(vehicle) },
        ).map { it.await() }
        val reading = ParkingReading.fromSources(sources)
        Log.i(TAG, "주차 판단=${reading.state} | " + sources.joinToString(" | ") { "${it.sourceName}: ${it.note}" })
        reading
    }

    // ------------------------------------------------------------------
    // 1·2. 안드로이드 표준 차량 속성 (android.car 라이브러리, 리플렉션으로 호출)
    //      컴파일 때 android.car 가 없어도 빌드되도록 리플렉션을 쓴다.
    // ------------------------------------------------------------------

    private suspend fun readStandardParkingBrake(): ParkingSourceResult {
        val name = "파킹브레이크 · 안드로이드 표준"
        return try {
            val isOn = withContext(Dispatchers.IO) {
                withStandardPropertyManager { manager ->
                    manager.javaClass
                        .getMethod("getBooleanProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(manager, PROP_PARKING_BRAKE_ON, AREA_GLOBAL) as Boolean
                }
            }
            ParkingSourceResult(name, Kind.PARKING_BRAKE, isOn, if (isOn) "걸림" else "풀림")
        } catch (e: Throwable) {
            failed(name, Kind.PARKING_BRAKE, e)
        }
    }

    private suspend fun readStandardGearInPark(): ParkingSourceResult {
        val name = "기어 P단 · 안드로이드 표준"
        return try {
            val gear = withContext(Dispatchers.IO) {
                withStandardPropertyManager { manager ->
                    manager.javaClass
                        .getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(manager, PROP_GEAR_SELECTION, AREA_GLOBAL) as Int
                }
            }
            val isPark = gear == STANDARD_GEAR_PARK
            ParkingSourceResult(name, Kind.GEAR_IN_PARK, isPark, "기어 값=$gear (${if (isPark) "P" else "P 아님"})")
        } catch (e: Throwable) {
            failed(name, Kind.GEAR_IN_PARK, e)
        }
    }

    /** android.car.Car 에 연결 → CarPropertyManager 로 작업 → 연결 해제 */
    private fun <T> withStandardPropertyManager(block: (Any) -> T): T {
        val carClass = Class.forName("android.car.Car")
        val car = carClass.getMethod("createCar", Context::class.java).invoke(null, appContext)
            ?: error("Car.createCar 가 null 을 돌려줌")
        try {
            val manager = carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
                ?: error("CarPropertyManager 없음")
            return block(manager)
        } finally {
            runCatching { carClass.getMethod("disconnect").invoke(car) }
        }
    }

    // ------------------------------------------------------------------
    // 3. Pleos Gear (deprecated 이지만 일반 권한이라 시도)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private suspend fun readPleosGearInPark(vehicle: Vehicle?): ParkingSourceResult {
        val name = "기어 P단 · Pleos Gear"
        if (vehicle == null) return ParkingSourceResult(name, Kind.GEAR_IN_PARK, null, "Vehicle SDK 연결 안 됨")
        return try {
            val outcome = awaitCallback<VehicleGear?> { ok, fail ->
                vehicle.getGear().getCurrentGearSelectionState(onComplete = ok, onFailed = fail)
            }
            when (outcome) {
                null -> ParkingSourceResult(name, Kind.GEAR_IN_PARK, null, "${timeoutMillis / 1000}초 안에 응답 없음")
                is CallbackOutcome.Failed -> failed(name, Kind.GEAR_IN_PARK, outcome.error)
                is CallbackOutcome.Done -> {
                    val gear = outcome.value
                    if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) {
                        ParkingSourceResult(name, Kind.GEAR_IN_PARK, null, "기어 값 없음 (${gear?.name})")
                    } else {
                        ParkingSourceResult(name, Kind.GEAR_IN_PARK, gear == VehicleGear.GEAR_PARK, gear.name)
                    }
                }
            }
        } catch (e: Throwable) {
            failed(name, Kind.GEAR_IN_PARK, e)
        }
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private sealed class CallbackOutcome<out T> {
        data class Done<T>(val value: T) : CallbackOutcome<T>()
        data class Failed(val error: Exception) : CallbackOutcome<Nothing>()
    }

    /** Pleos 콜백 API 를 기다린다. 시간 안에 응답이 없으면 null */
    private suspend fun <T> awaitCallback(
        call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): CallbackOutcome<T>? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            call(
                { value -> if (continuation.isActive) continuation.resume(CallbackOutcome.Done(value)) },
                { error -> if (continuation.isActive) continuation.resume(CallbackOutcome.Failed(error)) },
            )
        }
    }

    private fun failed(name: String, kind: Kind, error: Throwable): ParkingSourceResult {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        val note = when (cause) {
            is ClassNotFoundException -> "android.car 라이브러리 없음"
            is SecurityException -> "권한 없음 (${cause.message ?: "SecurityException"})"
            else -> "${cause.javaClass.simpleName}: ${cause.message ?: "(메시지 없음)"}"
        }
        Log.w(TAG, "$name 읽기 실패: $note")
        return ParkingSourceResult(name, kind, null, note)
    }

    private companion object {
        const val TAG = "ShimpyoParking"

        // android.car VehiclePropertyIds (AOSP 공개 값)
        const val PROP_PARKING_BRAKE_ON = 0x11200402
        const val PROP_GEAR_SELECTION = 0x11400400
        const val AREA_GLOBAL = 0

        // android.car VehicleGear.GEAR_PARK
        const val STANDARD_GEAR_PARK = 0x0004
    }
}
