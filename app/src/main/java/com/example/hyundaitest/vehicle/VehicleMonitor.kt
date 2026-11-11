package com.example.hyundaitest.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import android.util.Log
import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.safety.SafetyRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 휴식 중 감지한 차량 변화 (설계서 3장 휴식 중 감시, S-05, S-07) */
enum class VehicleAlert {
    /** 잠겨 있던 문이 풀림 → 휴식 중단 */
    DOOR_UNLOCKED,
    /** 닫혀 있던 문이 열림 → 휴식 중단 */
    DOOR_OPENED,
    /** 브레이크 페달을 밟음 → 휴식 중단 */
    BRAKE_PRESSED,
    /** 주차 상태가 풀림(기어가 P에서 벗어남 등) → 휴식 중단 */
    PARKING_RELEASED,
    /** 배터리가 하한선 아래로 내려감 → 알람을 바로 울려 깨움 */
    BATTERY_LOW,
}

/**
 * 휴식 중 차량 감시의 약속. NapController 는 휴식이 시작되면 startWatching, 끝나면 stopWatching 을 부른다.
 * onAlert 는 항상 메인 스레드에서 불린다.
 */
interface VehicleMonitor {
    fun startWatching(onAlert: (VehicleAlert) -> Unit)
    fun stopWatching()
}

/** 감시하지 않는 구현 (차량 없음·테스트) */
class NoVehicleMonitor : VehicleMonitor {
    override fun startWatching(onAlert: (VehicleAlert) -> Unit) = Unit
    override fun stopWatching() = Unit
}

/**
 * Pleos 차량 감시.
 *
 * 모두 "바뀌는 순간"만 알린다. 시작할 때부터 그 상태였으면 알리지 않는다.
 *  - 문 잠금: 리스너. 잠김(true) → 풀림(false)일 때. 보닛·트렁크는 제외.
 *  - 문 열림: doorCheckIntervalMillis 마다 위치를 읽음. 닫힘(최솟값) → 열림일 때. 보닛·트렁크는 제외.
 *  - 브레이크: 리스너. 안 밟음(false) → 밟음(true)일 때. 처음 값을 모르면 첫 값은 기준으로만 쓴다.
 *  - 주차 상태: parkingCheckIntervalMillis 마다 읽음. 주차 아님(NOT_PARKED)이 읽히면. (모름은 무시)
 *  - 배터리: batteryCheckIntervalMillis 마다 읽음. 하한선 위 → 하한선 이하로 내려갈 때.
 *
 * SDK 콜백은 다른 스레드에서 오므로, 상태 처리는 모두 메인 스레드로 옮겨서 한다.
 */
class PleosVehicleMonitor(
    private val vehicle: Vehicle,
    private val scope: CoroutineScope,
    private val parkingStatusReader: ParkingStatusReader,
    private val rules: SafetyRules,
    private val parkingCheckIntervalMillis: Long = 5_000L,
    private val batteryCheckIntervalMillis: Long = 10_000L,
    private val doorCheckIntervalMillis: Long = 2_000L,
) : VehicleMonitor {

    private var onAlert: ((VehicleAlert) -> Unit)? = null
    /** 감시를 시작·중지할 때마다 바뀌는 번호. 늦게 도착한 등록 작업이 옛 감시에 붙지 않게 한다. */
    private var generation = 0
    private val pollingJobs = mutableListOf<Job>()
    private val doorListeners = mutableListOf<Pair<DoorArea, DoorLockStateListener>>()
    private var brakeListener: BrakePedalStatusListener? = null

    private val lastDoorLocked = mutableMapOf<DoorArea, Boolean>()
    private var lastBrakePressed: Boolean? = null
    private var lastBatteryPercent: Int? = null

    override fun startWatching(onAlert: (VehicleAlert) -> Unit) {
        stopWatching()
        generation++
        this.onAlert = onAlert
        log("감시 시작")
        watchBrake()
        watchDoors()
        pollingJobs += scope.launch { watchParking() }
        pollingJobs += scope.launch { watchBattery() }
        pollingJobs += scope.launch { watchDoorOpening() }
    }

    override fun stopWatching() {
        generation++
        onAlert = null
        pollingJobs.forEach { it.cancel() }
        pollingJobs.clear()
        brakeListener?.let { runCatching { vehicle.getBrake().unregisterBrakePedalStatus(it) } }
        brakeListener = null
        doorListeners.forEach { (area, listener) -> runCatching { vehicle.getDoor().unregisterDoorLockState(area, listener) } }
        doorListeners.clear()
        lastDoorLocked.clear()
        lastBrakePressed = null
        lastBatteryPercent = null
    }

    // ------------------------------------------------------------------
    // 브레이크
    // ------------------------------------------------------------------

    private fun watchBrake() {
        val myGeneration = generation
        scope.launch {
            val initial = awaitPleos<Boolean?> { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus(ok, fail) }.valueOrNull()
            if (myGeneration != generation) return@launch // 그 사이 감시가 끝났거나 다시 시작됨
            lastBrakePressed = initial
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) {
                    onMain { handleBrake(status) }
                }

                override fun onFailed(e: Exception) {
                    Log.w(TAG, "브레이크 감시 오류: ${e.javaClass.simpleName}")
                }
            }
            try {
                vehicle.getBrake().registerBrakePedalStatus(listener)
                brakeListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "브레이크 감시 등록 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handleBrake(pressed: Boolean?) {
        if (pressed == null) return
        val before = lastBrakePressed
        lastBrakePressed = pressed
        if (before == false && pressed) alert(VehicleAlert.BRAKE_PRESSED)
    }

    // ------------------------------------------------------------------
    // 문 잠금
    // ------------------------------------------------------------------

    private fun watchDoors() {
        val myGeneration = generation
        scope.launch {
            val capability = awaitPleos<Pair<CapabilityConfig, List<DoorArea>>> { ok, fail ->
                vehicle.getDoor().checkDoorLockCapabilities(onComplete = { c, l -> ok(c to l) }, onFailed = fail)
            }.valueOrNull() ?: return@launch
            val cabinDoors = capability.second.filter { area ->
                DoorValue.HOOD !in area.values && DoorValue.REAR !in area.values
            }
            for (area in cabinDoors) {
                val initial = awaitPleos<Boolean?> { ok, fail -> vehicle.getDoor().getDoorLockState(area, ok, fail) }.valueOrNull()
                if (myGeneration != generation) return@launch // 그 사이 감시가 끝났거나 다시 시작됨
                initial?.let { lastDoorLocked[area] = it }
                val listener = object : DoorLockStateListener {
                    override fun onDoorLockStateUpdated(state: Boolean?) {
                        onMain { handleDoor(area, state) }
                    }

                    override fun onFailed(e: Exception) {
                        Log.w(TAG, "문 잠금 감시 오류: ${e.javaClass.simpleName}")
                    }
                }
                try {
                    vehicle.getDoor().registerDoorLockState(area, listener)
                    doorListeners += area to listener
                } catch (e: Exception) {
                    Log.w(TAG, "문 잠금 감시 등록 실패: ${e.javaClass.simpleName}")
                }
            }
            log("문 잠금 감시: ${doorListeners.size}곳")
        }
    }

    private fun handleDoor(area: DoorArea, locked: Boolean?) {
        if (locked == null) return
        val before = lastDoorLocked[area]
        lastDoorLocked[area] = locked
        if (before == true && !locked) alert(VehicleAlert.DOOR_UNLOCKED)
    }

    // ------------------------------------------------------------------
    // 문 열림 (주기적으로 읽기)
    // ------------------------------------------------------------------

    private suspend fun watchDoorOpening() {
        val doors = awaitPleos<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { ok, fail ->
            vehicle.getDoor().checkDoorPositionCapabilities(onComplete = { c, m -> ok(c to m) }, onFailed = fail)
        }.valueOrNull()?.second
            ?.filterKeys { area -> DoorValue.HOOD !in area.values && DoorValue.REAR !in area.values }
            .orEmpty()
        if (doors.isEmpty()) {
            log("문 열림 감시: 지원하는 문 없음")
            return
        }
        log("문 열림 감시: ${doors.size}곳")
        val wasOpen = mutableMapOf<DoorArea, Boolean>()
        while (scope.isActive && onAlert != null) {
            for ((area, bounds) in doors) {
                val position = awaitPleos<Int?> { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(area, ok, fail) }
                    .valueOrNull() ?: continue
                val isOpen = position > bounds.minValue
                val before = wasOpen[area]
                wasOpen[area] = isOpen
                if (before == false && isOpen) {
                    alert(VehicleAlert.DOOR_OPENED)
                    return
                }
            }
            delay(doorCheckIntervalMillis)
        }
    }

    // ------------------------------------------------------------------
    // 주차 상태·배터리 (주기적으로 읽기)
    // ------------------------------------------------------------------

    private suspend fun watchParking() {
        while (scope.isActive && onAlert != null) {
            delay(parkingCheckIntervalMillis)
            if (parkingStatusReader.readParkingStatus().state == ParkingState.NOT_PARKED) {
                alert(VehicleAlert.PARKING_RELEASED)
            }
        }
    }

    private suspend fun watchBattery() {
        lastBatteryPercent = readBattery()
        while (scope.isActive && onAlert != null) {
            delay(batteryCheckIntervalMillis)
            val now = readBattery() ?: continue
            val before = lastBatteryPercent
            lastBatteryPercent = now
            if (before != null && before > rules.batteryLowPercent && now <= rules.batteryLowPercent) {
                alert(VehicleAlert.BATTERY_LOW)
            }
        }
    }

    private suspend fun readBattery(): Int? =
        awaitPleos<Int?> { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }.valueOrNull()

    // ------------------------------------------------------------------

    private fun alert(alert: VehicleAlert) {
        val callback = onAlert ?: return
        log("감지: $alert")
        callback(alert)
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "ShimpyoMonitor"
    }
}

/**
 * 개발용: 실제 감시를 그대로 쓰면서, 버튼으로 차량 변화를 "흉내" 낼 수 있게 감싼 것.
 * 에뮬레이터 Virtual Console 로 문·배터리를 바꾸기 어려울 때, 앱이 올바르게 반응하는지 확인하는 용도.
 * SDK 에서 값을 읽는 부분은 시험하지 않는다(그 부분은 실제 감시가 그대로 한다).
 * 휴식 화면의 흉내 버튼은 디버그 빌드에서만 보인다.
 */
class SimulatableVehicleMonitor(private val real: VehicleMonitor) : VehicleMonitor {
    private var onAlert: ((VehicleAlert) -> Unit)? = null

    /** 지금 감시 중인지 (휴식 중에만 true) */
    val isWatching: Boolean get() = onAlert != null

    override fun startWatching(onAlert: (VehicleAlert) -> Unit) {
        this.onAlert = onAlert
        real.startWatching(onAlert)
    }

    override fun stopWatching() {
        onAlert = null
        real.stopWatching()
    }

    /** 차량에서 이 변화가 감지된 것처럼 알린다. 감시 중이 아니면 아무 일도 없다. */
    fun simulate(alert: VehicleAlert) {
        Log.i("ShimpyoMonitor", "개발용 흉내: $alert")
        onAlert?.invoke(alert)
    }
}
