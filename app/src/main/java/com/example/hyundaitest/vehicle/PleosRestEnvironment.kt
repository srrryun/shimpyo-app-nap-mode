package com.example.hyundaitest.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.util.Log
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.safety.SafetyRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 휴식 환경 목표값 (설계서 D-2 미정 → 임시값). 값을 바꿀 때는 여기만 고친다.
 */
data class RestComfortTargets(
    /** 휴식 중 무드등 밝기 (지원 범위의 %) */
    val restAmbientPercent: Int = 20,
    /** 깨울 때 무드등 밝기 (지원 범위의 %) */
    val wakeAmbientPercent: Int = 70,
    /** 깨울 때 무드등을 몇 단계로 나눠 밝힐지, 단계 간격 */
    val ambientRampSteps: Int = 4,
    val ambientRampIntervalMillis: Long = 5_000L,
    val restTemperatureCelsius: Float = 24.0f,
    val restFanSpeed: Int = 2,
    val restRecirculation: Boolean = true,
    /** 깨울 때 온도를 휴식 온도에서 얼마나 바꿀지 */
    val wakeTemperatureDeltaCelsius: Float = -1.0f,
    /** 휴식 시작 때 등받이를 눕히는 시간 (2초씩 나눠 움직임) */
    val restSeatReclineMillis: Long = 4_000L,
    /** 제어 요청 후 다시 읽어 반영 여부를 확인하기까지 기다리는 시간 */
    val readBackDelayMillis: Long = 700L,
)

/**
 * Pleos Vehicle SDK 로 휴식 환경을 제어하는 구현 (설계서 7장, 5장 ①⑤, 8장 S-01~S-06).
 *
 * 규칙
 *  - 승인한 항목(approvedItems)만 제어한다 (S-02).
 *  - 지원 확인(check…Capabilities)에서 읽기+쓰기가 되는 항목만 제어한다. 나머지는 "미지원".
 *  - 시작 전 값을 스냅샷으로 저장(앱이 꺼져도 남도록 SharedPreferences 에도)하고, 끝나면 되돌린다 (S-06).
 *  - 등받이는 SeatMover 로만 움직인다: 2초씩, 매번 STOP, 최대 15초, 주차가 풀리면 멈춤 (S-01, S-03).
 *  - 결과는 요청 후 다시 읽어서 "반영 확인 / 반영 안 됨"을 구분한다. 에뮬레이터는 대부분 "반영 안 됨"이 정상.
 *  - 잠금 해제·시동·조향·제동·주행 함수는 쓰지 않는다 (S-10).
 */
class PleosRestEnvironment(
    context: Context,
    private val vehicle: Vehicle,
    private val scope: CoroutineScope,
    private val rules: SafetyRules,
    private val isStillParked: suspend () -> Boolean,
    private val targets: RestComfortTargets = RestComfortTargets(),
) : RestEnvironment {

    private val snapshotStore = ComfortSnapshotStore(context)
    private val seatMover = SeatMover(PleosSeatActuator(vehicle, SEAT_AREA), rules, canKeepMoving = isStillParked)
    private val areaLock = Mutex()
    private var areas: ControlAreas? = null

    /** 이번 휴식의 스냅샷. 앱 재실행 뒤에는 저장소에서 다시 읽는다. */
    private var snapshot: ComfortSnapshot? = null
    private var ambientRampJob: Job? = null

    override val isVehicleControlEnabled: Boolean
        get() = currentSnapshot()?.approvedItems?.isNotEmpty() == true

    // ------------------------------------------------------------------
    // 지원 항목
    // ------------------------------------------------------------------

    override suspend fun findSupportedItems(): Set<ComfortItem> {
        val found = resolveAreas()
        return buildSet {
            if (found.ambient != null) add(ComfortItem.AMBIENT_LIGHT)
            if (found.hasAnyClimate) add(ComfortItem.CLIMATE)
            if (found.seatSupported) add(ComfortItem.SEAT_BACKREST)
        }
    }

    // ------------------------------------------------------------------
    // 휴식 시작: 스냅샷 → 세팅
    // ------------------------------------------------------------------

    override suspend fun prepareForRest(session: NapSession, approvedItems: Set<ComfortItem>): List<ControlResult> {
        val found = resolveAreas()
        val supported = findSupportedItems()
        val toControl = approvedItems intersect supported

        val saved = takeSnapshot(found, toControl)
        snapshot = saved
        snapshotStore.save(saved)
        log("휴식 환경 세팅 시작: 승인 ${approvedItems.map { it.name }}, 지원 ${supported.map { it.name }}")

        val results = mutableListOf<ControlResult>()
        for (item in approvedItems - supported) {
            results += ControlResult(item.label, ControlStatus.UNSUPPORTED)
        }
        if (ComfortItem.AMBIENT_LIGHT in toControl) {
            found.ambient?.let { results += setAmbient(it, percentOf(it.bounds, targets.restAmbientPercent), "무드등 낮추기") }
        }
        if (ComfortItem.CLIMATE in toControl) {
            results += setClimateForRest(found)
        }
        if (ComfortItem.SEAT_BACKREST in toControl) {
            results += reclineSeat()
        }
        return results
    }

    // ------------------------------------------------------------------
    // 깨우기 단계 (설계서 5장 ①)
    // ------------------------------------------------------------------

    override suspend fun wakeStepLightUp() {
        val saved = currentSnapshot() ?: return
        if (ComfortItem.AMBIENT_LIGHT !in saved.approvedItems) return
        val ambient = resolveAreas().ambient ?: return
        val from = percentOf(ambient.bounds, targets.restAmbientPercent)
        val to = percentOf(ambient.bounds, targets.wakeAmbientPercent)
        ambientRampJob?.cancel()
        // 서서히 밝히기는 따로 돌려서 다음 깨우기 단계를 늦추지 않는다
        ambientRampJob = scope.launch {
            val steps = targets.ambientRampSteps.coerceAtLeast(1)
            for (step in 1..steps) {
                val value = from + ((to - from) * step / steps.toFloat()).roundToInt()
                setAmbient(ambient, value, "무드등 밝히기 $step/$steps")
                if (step < steps) delay(targets.ambientRampIntervalMillis)
            }
        }
    }

    override suspend fun wakeStepFreshAir() {
        val saved = currentSnapshot() ?: return
        if (ComfortItem.CLIMATE !in saved.approvedItems) return
        val found = resolveAreas()
        found.recirculation?.let { area ->
            setBoolean("내기 순환 끄기(외기)", false,
                set = { value, ok, fail -> vehicle.getHVAC().setHvacRecirculation(area, value, onComplete = { _, v -> ok(v) }, onFailed = fail) },
                read = { ok, fail -> vehicle.getHVAC().getHvacRecirculationState(area, ok, fail) })
        }
        found.temperature?.let { temp ->
            val target = (targets.restTemperatureCelsius + targets.wakeTemperatureDeltaCelsius)
                .coerceIn(temp.bounds.minValue, temp.bounds.maxValue)
            setTemperature(temp.area, target, "온도 조금 낮추기")
        }
    }

    override suspend fun wakeStepRaiseSeat() {
        val saved = currentSnapshot() ?: return
        if (ComfortItem.SEAT_BACKREST !in saved.approvedItems) return
        raiseSeatBack(saved)
    }

    // ------------------------------------------------------------------
    // 복원 (S-06)
    // ------------------------------------------------------------------

    override suspend fun restoreOriginalState(): List<ControlResult> {
        val saved = currentSnapshot() ?: return emptyList()
        ambientRampJob?.cancel()
        val found = resolveAreas()
        val results = mutableListOf<ControlResult>()

        if (ComfortItem.AMBIENT_LIGHT in saved.approvedItems) {
            val ambient = found.ambient
            val original = saved.ambientBrightness
            if (ambient != null && original != null) results += setAmbient(ambient, original, "무드등 원래대로")
        }
        if (ComfortItem.CLIMATE in saved.approvedItems) {
            results += restoreClimate(found, saved)
        }
        if (ComfortItem.SEAT_BACKREST in saved.approvedItems) {
            results += raiseSeatBack(saved)
        }

        snapshot = null
        snapshotStore.clear()
        log("원래 상태 복원 끝: ${results.joinToString { "${it.itemName}=${it.status}" }}")
        return results
    }

    override fun stopAllMovementNow() {
        ambientRampJob?.cancel()
        seatMover.stopNow()
        log("모든 움직임 정지 요청")
    }

    // ------------------------------------------------------------------
    // 공조
    // ------------------------------------------------------------------

    private suspend fun setClimateForRest(found: ControlAreas): List<ControlResult> {
        val results = mutableListOf<ControlResult>()
        found.power?.let { area ->
            results += setBoolean("공조 켜기", true,
                set = { value, ok, fail -> vehicle.getHVAC().setHvacPower(area, value, onComplete = { _, v -> ok(v) }, onFailed = fail) },
                read = { ok, fail -> vehicle.getHVAC().getHvacPowerState(area, ok, fail) })
        }
        found.temperature?.let { temp ->
            val target = targets.restTemperatureCelsius.coerceIn(temp.bounds.minValue, temp.bounds.maxValue)
            results += setTemperature(temp.area, target, "온도 ${target}℃")
        }
        found.fan?.let { fan ->
            val target = targets.restFanSpeed.coerceIn(fan.bounds.minValue, fan.bounds.maxValue)
            results += setFanSpeed(fan.area, target, "풍량 $target")
        }
        found.recirculation?.let { area ->
            results += setBoolean("내기 순환", targets.restRecirculation,
                set = { value, ok, fail -> vehicle.getHVAC().setHvacRecirculation(area, value, onComplete = { _, v -> ok(v) }, onFailed = fail) },
                read = { ok, fail -> vehicle.getHVAC().getHvacRecirculationState(area, ok, fail) })
        }
        return results
    }

    private suspend fun restoreClimate(found: ControlAreas, saved: ComfortSnapshot): List<ControlResult> {
        val results = mutableListOf<ControlResult>()
        val temp = found.temperature
        val tempOriginal = saved.temperatureCelsius
        if (temp != null && tempOriginal != null) results += setTemperature(temp.area, tempOriginal, "온도 원래대로")
        val fan = found.fan
        val fanOriginal = saved.fanSpeed
        if (fan != null && fanOriginal != null) results += setFanSpeed(fan.area, fanOriginal, "풍량 원래대로")
        val recirculation = found.recirculation
        val recirculationOriginal = saved.recirculation
        if (recirculation != null && recirculationOriginal != null) {
            results += setBoolean("내기 순환 원래대로", recirculationOriginal,
                set = { value, ok, fail -> vehicle.getHVAC().setHvacRecirculation(recirculation, value, onComplete = { _, v -> ok(v) }, onFailed = fail) },
                read = { ok, fail -> vehicle.getHVAC().getHvacRecirculationState(recirculation, ok, fail) })
        }
        // 전원은 마지막에: 원래 꺼져 있었으면 끈다
        val power = found.power
        val powerOriginal = saved.hvacPower
        if (power != null && powerOriginal != null) {
            results += setBoolean("공조 전원 원래대로", powerOriginal,
                set = { value, ok, fail -> vehicle.getHVAC().setHvacPower(power, value, onComplete = { _, v -> ok(v) }, onFailed = fail) },
                read = { ok, fail -> vehicle.getHVAC().getHvacPowerState(power, ok, fail) })
        }
        return results
    }

    private suspend fun setTemperature(area: SeatArea, value: Float, name: String): ControlResult {
        val request = awaitPleos<Float?> { ok, fail ->
            vehicle.getHVAC().setHvacTemperature(area, value, TemperatureUnit.CELSIUS, onComplete = { _, v -> ok(v) }, onFailed = fail)
        }
        if (request !is PleosResult.Success) return failed(name, request)
        delay(targets.readBackDelayMillis)
        val now = awaitPleos<Float?> { ok, fail ->
            vehicle.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail)
        }.valueOrNull()
        return judge(name, applied = now != null && abs(now - value) < TEMPERATURE_TOLERANCE, readBack = now)
    }

    private suspend fun setFanSpeed(area: SeatArea, value: Int, name: String): ControlResult {
        val request = awaitPleos<Int?> { ok, fail ->
            vehicle.getHVAC().setHvacFanSpeed(area, value, onComplete = { _, v -> ok(v) }, onFailed = fail)
        }
        if (request !is PleosResult.Success) return failed(name, request)
        delay(targets.readBackDelayMillis)
        val now = awaitPleos<Int?> { ok, fail -> vehicle.getHVAC().getHvacFanSpeed(area, ok, fail) }.valueOrNull()
        return judge(name, applied = now == value, readBack = now)
    }

    private suspend fun setBoolean(
        name: String,
        value: Boolean,
        set: (value: Boolean, ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit,
        read: (ok: (Boolean?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): ControlResult {
        val request = awaitPleos<Boolean?> { ok, fail -> set(value, ok, fail) }
        if (request !is PleosResult.Success) return failed(name, request)
        delay(targets.readBackDelayMillis)
        val now = awaitPleos<Boolean?> { ok, fail -> read(ok, fail) }.valueOrNull()
        return judge(name, applied = now == value, readBack = now)
    }

    // ------------------------------------------------------------------
    // 무드등
    // ------------------------------------------------------------------

    private suspend fun setAmbient(ambient: BoundedArea<Int>, value: Int, name: String): ControlResult {
        val target = value.coerceIn(ambient.bounds.minValue, ambient.bounds.maxValue)
        val request = awaitPleos<Int> { ok, fail ->
            vehicle.getLight().setAmbientLightBrightness(ambient.area, target, onComplete = { _, v -> ok(v) }, onFailed = fail)
        }
        if (request !is PleosResult.Success) return failed(name, request)
        delay(targets.readBackDelayMillis)
        val now = awaitPleos<Int?> { ok, fail -> vehicle.getLight().getAmbientLightBrightness(ambient.area, ok, fail) }.valueOrNull()
        return judge(name, applied = now == target, readBack = now)
    }

    // ------------------------------------------------------------------
    // 등받이
    // ------------------------------------------------------------------

    private suspend fun reclineSeat(): ControlResult {
        val before = PleosSeatActuator(vehicle, SEAT_AREA).readPosition()
        val moved = seatMover.moveFor(SeatDirection.RECLINE, targets.restSeatReclineMillis)
        currentSnapshot()?.let { saved ->
            val updated = saved.copy(seatReclinedMillis = moved)
            snapshot = updated
            snapshotStore.save(updated)
        }
        val after = PleosSeatActuator(vehicle, SEAT_AREA).readPosition()
        if (moved == 0L) return ControlResult("등받이 눕히기", ControlStatus.NOT_APPLIED, "주차 상태가 아니거나 이동 요청 실패")
        return judge("등받이 눕히기", applied = before != null && after != null && before != after, readBack = after)
    }

    /** 원래 위치로 세운다. 위치로 맞출 수 없으면(에뮬레이터) 눕힌 시간만큼 반대로 움직인다. */
    private suspend fun raiseSeatBack(saved: ComfortSnapshot): ControlResult {
        val original = saved.seatPosition
        val reachedByPosition = original != null && seatMover.moveTo(original)
        if (!reachedByPosition && saved.seatReclinedMillis > 0) {
            seatMover.moveFor(SeatDirection.RAISE, saved.seatReclinedMillis)
        }
        // 한 번 세웠으면 다시 세우지 않도록 기록
        val updated = saved.copy(seatReclinedMillis = 0L)
        if (snapshot != null) {
            snapshot = updated
            snapshotStore.save(updated)
        }
        val now = PleosSeatActuator(vehicle, SEAT_AREA).readPosition()
        return judge("등받이 세우기", applied = original != null && now == original, readBack = now)
    }

    // ------------------------------------------------------------------
    // 스냅샷
    // ------------------------------------------------------------------

    private fun currentSnapshot(): ComfortSnapshot? {
        if (snapshot == null) snapshot = snapshotStore.load()
        return snapshot
    }

    private suspend fun takeSnapshot(found: ControlAreas, items: Set<ComfortItem>): ComfortSnapshot {
        val hvac = vehicle.getHVAC()
        val climate = ComfortItem.CLIMATE in items
        return ComfortSnapshot(
            approvedItems = items,
            ambientBrightness = if (ComfortItem.AMBIENT_LIGHT in items) found.ambient?.let { a ->
                awaitPleos<Int?> { ok, fail -> vehicle.getLight().getAmbientLightBrightness(a.area, ok, fail) }.valueOrNull()
            } else null,
            hvacPower = if (climate) found.power?.let { a -> awaitPleos<Boolean?> { ok, fail -> hvac.getHvacPowerState(a, ok, fail) }.valueOrNull() } else null,
            temperatureCelsius = if (climate) found.temperature?.let { t ->
                awaitPleos<Float?> { ok, fail -> hvac.getHvacTemperature(t.area, TemperatureUnit.CELSIUS, ok, fail) }.valueOrNull()
            } else null,
            fanSpeed = if (climate) found.fan?.let { f -> awaitPleos<Int?> { ok, fail -> hvac.getHvacFanSpeed(f.area, ok, fail) }.valueOrNull() } else null,
            recirculation = if (climate) found.recirculation?.let { a ->
                awaitPleos<Boolean?> { ok, fail -> hvac.getHvacRecirculationState(a, ok, fail) }.valueOrNull()
            } else null,
            seatPosition = if (ComfortItem.SEAT_BACKREST in items) PleosSeatActuator(vehicle, SEAT_AREA).readPosition() else null,
            seatReclinedMillis = 0L,
        )
    }

    // ------------------------------------------------------------------
    // 지원 영역 찾기 (한 번만)
    // ------------------------------------------------------------------

    private suspend fun resolveAreas(): ControlAreas = areaLock.withLock {
        areas?.let { return@withLock it }
        val light = vehicle.getLight()
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()

        val ambient = awaitPleos<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities(onComplete = { c, m -> ok(c to m) }, onFailed = fail)
        }.valueOrNull()?.takeIf { canReadWrite(it.first) }?.second?.entries?.firstOrNull()
            ?.let { BoundedArea(it.key, it.value) }

        val power = awaitPleos<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacPowerCapabilities(onComplete = { c, l -> ok(c to l) }, onFailed = fail)
        }.valueOrNull()?.takeIf { canReadWrite(it.first) }?.second?.let { preferDriver(it) }

        val temperature = awaitPleos<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(onComplete = { c, m, _ -> ok(c to m) }, onFailed = fail)
        }.valueOrNull()?.takeIf { canReadWrite(it.first) }?.second?.let { map ->
            preferDriver(map.keys.toList())?.let { area -> map[area]?.let { BoundedArea(area, it) } }
        }

        val fan = awaitPleos<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(onComplete = { c, m -> ok(c to m) }, onFailed = fail)
        }.valueOrNull()?.takeIf { canReadWrite(it.first) }?.second?.let { map ->
            preferDriver(map.keys.toList())?.let { area -> map[area]?.let { BoundedArea(area, it) } }
        }

        val recirculation = awaitPleos<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacRecirculationCapabilities(onComplete = { c, l -> ok(c to l) }, onFailed = fail)
        }.valueOrNull()?.takeIf { canReadWrite(it.first) }?.second?.let { preferDriver(it) }

        val seatMove = awaitPleos<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkMoveSeatBackrestAngleCapabilities(onComplete = { c, l -> ok(c to l) }, onFailed = fail)
        }.valueOrNull()
        val seatSupported = seatMove != null &&
            seatMove.first.accessType != AccessType.NONE &&
            seatMove.second.any { SeatValue.ROW_1_LEFT in it.values }

        ControlAreas(ambient, power, temperature, fan, recirculation, seatSupported).also {
            areas = it
            log("지원 영역: 무드등=${ambient != null}, 공조 전원=${power != null}, 온도=${temperature != null}, 풍량=${fan != null}, 내기순환=${recirculation != null}, 등받이=$seatSupported")
        }
    }

    private fun canReadWrite(config: CapabilityConfig) = config.accessType == AccessType.READ_WRITE

    /** 운전석(ROW_1_LEFT)을 포함한 영역이 있으면 그것, 없으면 첫 번째 */
    private fun preferDriver(list: List<SeatArea>): SeatArea? =
        list.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: list.firstOrNull()

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private fun percentOf(bounds: AreaBounds<Int>, percent: Int): Int =
        bounds.minValue + ((bounds.maxValue - bounds.minValue) * percent / 100f).roundToInt()

    private fun judge(name: String, applied: Boolean, readBack: Any?): ControlResult {
        val status = if (applied) ControlStatus.CONFIRMED else ControlStatus.NOT_APPLIED
        val result = ControlResult(name, status, detail = "다시 읽은 값: ${readBack ?: "-"}")
        log("$name → ${status.label} (${result.detail})")
        return result
    }

    private fun failed(name: String, result: PleosResult<*>): ControlResult {
        log("$name 요청 실패: ${result.describe()}")
        return ControlResult(name, ControlStatus.NOT_APPLIED, detail = result.describe())
    }

    private fun log(message: String) = Log.i(TAG, message)

    private data class BoundedArea<T>(val area: SeatArea, val bounds: AreaBounds<T>)

    private data class ControlAreas(
        val ambient: BoundedArea<Int>?,
        val power: SeatArea?,
        val temperature: BoundedArea<Float>?,
        val fan: BoundedArea<Int>?,
        val recirculation: SeatArea?,
        val seatSupported: Boolean,
    ) {
        val hasAnyClimate: Boolean get() = power != null || temperature != null || fan != null || recirculation != null
    }

    companion object {
        private const val TAG = "ShimpyoComfort"
        private const val TEMPERATURE_TOLERANCE = 0.26f
        private val SEAT_AREA = SeatArea(SeatValue.ROW_1_LEFT)

        /** 주차 상태 확인 함수를 만드는 도우미: 주차 아님이 확실할 때만 false (모름은 막지 않음) */
        fun parkedCheckFrom(reader: ParkingStatusReader): suspend () -> Boolean = {
            reader.readParkingStatus().state != ParkingState.NOT_PARKED
        }
    }
}

/** Pleos 등받이 구동부 (moveSeatBackrestAngle + 위치 읽기) */
class PleosSeatActuator(private val vehicle: Vehicle, private val area: SeatArea) : SeatActuator {
    override suspend fun readPosition(): Int? = awaitPleos<Int?> { ok, fail ->
        vehicle.getSeat().getCurrentSeatBackrestAnglePosition(area = area, onComplete = ok, onFailed = fail)
    }.valueOrNull()

    override suspend fun startMoving(direction: SeatDirection): Boolean {
        val control = when (direction) {
            SeatDirection.RECLINE -> MoveControl.BACKWARD
            SeatDirection.RAISE -> MoveControl.FORWARD
        }
        val result = awaitPleos<Any?> { ok, fail ->
            vehicle.getSeat().moveSeatBackrestAngle(area = area, value = control, onComplete = { _, v -> ok(v) }, onFailed = fail)
        }
        return result is PleosResult.Success
    }

    override fun requestStop() {
        try {
            vehicle.getSeat().moveSeatBackrestAngle(
                area = area,
                value = MoveControl.STOP,
                onComplete = { _, _ -> },
                onFailed = { e -> Log.w("ShimpyoComfort", "등받이 정지 요청 실패: ${e.javaClass.simpleName}") },
            )
        } catch (e: Exception) {
            Log.w("ShimpyoComfort", "등받이 정지 요청 예외: ${e.javaClass.simpleName}")
        }
    }
}

/** 이번 휴식 전에 읽어 둔 값 (복원용) */
data class ComfortSnapshot(
    val approvedItems: Set<ComfortItem>,
    val ambientBrightness: Int?,
    val hvacPower: Boolean?,
    val temperatureCelsius: Float?,
    val fanSpeed: Int?,
    val recirculation: Boolean?,
    val seatPosition: Int?,
    /** 실제로 눕힌 시간. 위치를 못 읽는 차에서 반대로 세울 때 쓴다. */
    val seatReclinedMillis: Long,
)

/** 스냅샷 저장소 — 휴식 중 앱이 꺼져도 복원할 수 있게 (설계서 9.2 진행 중 상태) */
class ComfortSnapshotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shimpyo_comfort", Context.MODE_PRIVATE)

    fun save(snapshot: ComfortSnapshot) {
        val json = JSONObject()
            .put("approvedItems", JSONArray(snapshot.approvedItems.map { it.name }))
            .put("ambientBrightness", snapshot.ambientBrightness ?: JSONObject.NULL)
            .put("hvacPower", snapshot.hvacPower ?: JSONObject.NULL)
            .put("temperatureCelsius", snapshot.temperatureCelsius?.toDouble() ?: JSONObject.NULL)
            .put("fanSpeed", snapshot.fanSpeed ?: JSONObject.NULL)
            .put("recirculation", snapshot.recirculation ?: JSONObject.NULL)
            .put("seatPosition", snapshot.seatPosition ?: JSONObject.NULL)
            .put("seatReclinedMillis", snapshot.seatReclinedMillis)
        prefs.edit().putString(KEY, json.toString()).commit()
    }

    fun load(): ComfortSnapshot? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(text)
            val itemsArray = json.optJSONArray("approvedItems") ?: JSONArray()
            val items = (0 until itemsArray.length())
                .mapNotNull { index -> ComfortItem.entries.firstOrNull { it.name == itemsArray.optString(index) } }
                .toSet()
            ComfortSnapshot(
                approvedItems = items,
                ambientBrightness = json.intOrNull("ambientBrightness"),
                hvacPower = if (json.isNull("hvacPower")) null else json.optBoolean("hvacPower"),
                temperatureCelsius = if (json.isNull("temperatureCelsius")) null else json.optDouble("temperatureCelsius").toFloat(),
                fanSpeed = json.intOrNull("fanSpeed"),
                recirculation = if (json.isNull("recirculation")) null else json.optBoolean("recirculation"),
                seatPosition = json.intOrNull("seatPosition"),
                seatReclinedMillis = json.optLong("seatReclinedMillis", 0L)
                    .coerceIn(0L, SafetyRules().seatMoveTotalMaxMillis), // 저장값이 조작돼도 15초를 넘지 않게
            )
        } catch (e: Exception) {
            Log.w("ShimpyoComfort", "스냅샷을 읽지 못했어요: ${e.javaClass.simpleName}")
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name) || !has(name)) null else optInt(name)

    private companion object {
        const val KEY = "snapshot"
    }
}
