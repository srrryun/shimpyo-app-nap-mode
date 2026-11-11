package com.example.hyundaitest.nap

import com.example.hyundaitest.data.BambooRepository
import com.example.hyundaitest.data.NapSessionRepository
import com.example.hyundaitest.data.RestHistoryRepository
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestRecord
import com.example.hyundaitest.data.model.StopReason
import com.example.hyundaitest.safety.SafetyDecision
import com.example.hyundaitest.safety.SafetyGuard
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.ControlResult
import com.example.hyundaitest.vehicle.NoVehicleMonitor
import com.example.hyundaitest.vehicle.RestEnvironment
import com.example.hyundaitest.vehicle.VehicleAlert
import com.example.hyundaitest.vehicle.VehicleMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 휴식 흐름을 혼자 책임지는 상태 머신 (설계서 11장).
 *
 * 화면이 부르는 함수:
 *   startNap()                  휴식 시작
 *   stopNapNow()                즉시 중단
 *   confirmAwake()              "깨어났어요"
 *   restoreVehicleAfterStop()   중단 후 "원래대로 되돌리기"
 *   finishRest()                휴식 마치기 → 기록 저장
 *   returnToIdle()              완료 화면에서 홈으로
 *   resumeUnfinishedSession()   앱 시작 시, 끝나지 않은 휴식 복구
 *
 * 차량 감시(VehicleMonitor)가 부르는 것: handleVehicleAlert() → 중단 또는 조기 기상(wakeUpEarly)
 *
 * 규칙: 상태(_state)를 바꾸는 곳은 이 클래스뿐이다. 차량 제어는 RestEnvironment 를 통해서만 한다.
 *       휴식 중(Resting·AlarmRinging)에만 차량을 감시한다.
 */
class NapController(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val rules: SafetyRules,
    private val safetyGuard: SafetyGuard,
    private val alarmSound: AlarmSound,
    private val keepAlive: RestKeepAlive,
    private val restEnvironment: RestEnvironment,
    private val sessionRepository: NapSessionRepository,
    private val historyRepository: RestHistoryRepository,
    private val bambooRepository: BambooRepository,
    private val vehicleMonitor: VehicleMonitor = NoVehicleMonitor(),
    private val wakeSteps: List<WakeStep> = WakeStep.defaultSteps(),
    private val logger: NapEventLogger = NapEventLogger.NONE,
) {
    private val _state = MutableStateFlow<NapState>(NapState.Idle)

    /** 화면은 이 값을 구독해서 그린다. */
    val state: StateFlow<NapState> = _state.asStateFlow()

    private val _environmentResults = MutableStateFlow<List<ControlResult>>(emptyList())

    /** 휴식 시작 때 휴식 환경 세팅 결과 (휴식 화면에 표시) */
    val environmentResults: StateFlow<List<ControlResult>> = _environmentResults.asStateFlow()

    /** 휴식 타이머(깨우기 단계 → 알람) 작업. 중단·기상 시 취소한다. */
    private var restTimerJob: Job? = null

    /** 휴식 환경 세팅 작업. 중단·기상 시 취소한다. */
    private var prepareJob: Job? = null

    // ------------------------------------------------------------------
    // 휴식 시작
    // ------------------------------------------------------------------

    suspend fun startNap(request: NapStartRequest): StartNapResult {
        val decision = safetyGuard.checkBeforeStart(request, isRestInProgress = _state.value.isRestInProgress)
        if (decision is SafetyDecision.Blocked) {
            logger.log("휴식 시작 거절: ${decision.reasons}")
            return StartNapResult.Blocked(decision.reasons)
        }

        val now = timeSource.nowMillis()
        val bambooNoteId = saveBambooTextIfPresent(request.bambooText, now)
        val session = NapSession(
            id = UUID.randomUUID().toString(),
            presetName = request.plan.presetName,
            plannedDurationMillis = request.plan.durationMillis,
            startedAtMillis = now,
            endsAtMillis = now + request.plan.durationMillis,
            bambooNoteId = bambooNoteId,
            isTestRun = request.plan.isTestRun,
        )

        sessionRepository.saveActiveSession(session)
        keepAlive.startKeepingAlive(session.endsAtMillis)
        _state.value = NapState.Resting(session, wakeGuidance = null)
        logger.log("휴식 시작: ${session.presetName}, ${session.plannedDurationMillis}ms")

        startWatchingVehicle()
        startRestTimer(session)
        _environmentResults.value = emptyList()
        // 세팅은 시간이 걸릴 수 있어서(등받이 4초 등) 타이머와 따로 돌린다
        prepareJob = scope.launch {
            val results = runSafely("휴식 환경 세팅") {
                restEnvironment.prepareForRest(session, request.plan.approvedComfortItems)
            }.orEmpty()
            if (_state.value.activeSessionOrNull?.id == session.id) _environmentResults.value = results
        }
        return StartNapResult.Started(session)
    }

    private suspend fun saveBambooTextIfPresent(text: String?, now: Long): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return bambooRepository.saveNewNote(trimmed, now).id // 내용은 로그에 남기지 않는다
    }

    // ------------------------------------------------------------------
    // 타이머: 깨우기 단계 → 종료 시각 → 알람
    // ------------------------------------------------------------------

    private fun startRestTimer(session: NapSession) {
        restTimerJob?.cancel()
        restTimerJob = scope.launch {
            runWakeSteps(session)
            waitUntil(session.endsAtMillis)
            ringAlarmUntilAwake(session)
        }
    }

    private suspend fun runWakeSteps(session: NapSession) {
        val schedule = WakeStep.scheduleFor(
            steps = wakeSteps,
            restDurationMillis = session.plannedDurationMillis,
            fullSequenceMinRestMillis = rules.fullWakeSequenceMinRestMillis,
        )
        for (scheduled in schedule) {
            val runAtMillis = session.endsAtMillis - scheduled.millisBeforeEnd
            if (runAtMillis < timeSource.nowMillis() - rules.skipPastWakeStepToleranceMillis) {
                // 앱 재실행 등으로 이미 지난 단계는 다시 실행하지 않는다 (시트 이동 재개 금지)
                logger.log("지난 깨우기 단계 건너뜀: ${scheduled.step.id}")
                continue
            }
            waitUntil(runAtMillis)
            _state.value = NapState.Resting(session, wakeGuidance = scheduled.step.guidanceFor(restEnvironment))
            logger.log("깨우기 단계 실행: ${scheduled.step.id}")
            runSafely("깨우기 단계 ${scheduled.step.id}") { scheduled.step.action(restEnvironment) }
        }
    }

    /**
     * 1분 간격으로 최대 alarmMaxRings 번 울린다. 그래도 응답이 없으면 알람을 멈추고 화면은 유지.
     * @param earlyWakeReason 예정보다 일찍 깨우는 이유 (배터리 부족 등). 정상 종료면 null
     */
    private suspend fun ringAlarmUntilAwake(session: NapSession, earlyWakeReason: StopReason? = null) {
        val maxRings = rules.alarmMaxRings
        for (ringNumber in 1..maxRings) {
            _state.value = NapState.AlarmRinging(session, ringNumber, maxRings, hasGivenUp = false, earlyWakeReason = earlyWakeReason)
            logger.log("알람 $ringNumber/$maxRings")
            alarmSound.startRinging()
            try {
                delay(rules.alarmRingDurationMillis)
            } finally {
                alarmSound.stopRinging() // 취소(깨어났어요·중단)되어도 소리는 반드시 끈다
            }
            if (ringNumber < maxRings) {
                delay((rules.alarmRepeatIntervalMillis - rules.alarmRingDurationMillis).coerceAtLeast(0))
            }
        }
        _state.value = NapState.AlarmRinging(session, maxRings, maxRings, hasGivenUp = true, earlyWakeReason = earlyWakeReason)
        logger.log("알람 최대 횟수 도달, 화면 유지")
    }

    private suspend fun waitUntil(targetMillis: Long) {
        val waitMillis = targetMillis - timeSource.nowMillis()
        if (waitMillis > 0) delay(waitMillis)
    }

    // ------------------------------------------------------------------
    // 사용자 행동
    // ------------------------------------------------------------------

    /** "깨어났어요" — 알람이 울리는 중(또는 울림을 멈춘 뒤)에만 받는다. */
    fun confirmAwake() {
        val current = _state.value as? NapState.AlarmRinging ?: return
        vehicleMonitor.stopWatching()
        cancelTimerAndAlarm()
        val awake = NapState.AwakeCare(current.session, awakeConfirmedAtMillis = timeSource.nowMillis())
        _state.value = awake
        logger.log("기상 확인")

        // 깨어난 뒤 원래 상태로 복원 (S-06). 결과는 화면에 표시한다.
        scope.launch {
            val results = runSafely("원래 상태 복원") { restEnvironment.restoreOriginalState() }.orEmpty()
            val latest = _state.value
            if (latest is NapState.AwakeCare && latest.session.id == awake.session.id) {
                _state.value = latest.copy(restoreResults = results)
            }
        }
    }

    /** 즉시 중단 (S-05). 휴식 중·알람 중에만 동작한다. 2단계에서는 차량 감시도 이 함수를 부른다. */
    fun stopNapNow(reason: StopReason) {
        val current = _state.value
        val session = when (current) {
            is NapState.Resting -> current.session
            is NapState.AlarmRinging -> current.session
            else -> return
        }
        restEnvironment.stopAllMovementNow() // 가장 먼저 움직임부터 멈춘다
        vehicleMonitor.stopWatching()
        cancelTimerAndAlarm()
        _state.value = NapState.Stopped(session, reason, stoppedAtMillis = timeSource.nowMillis())
        logger.log("휴식 중단: $reason")
    }

    /**
     * 예정보다 일찍 깨운다 (배터리 부족 등). 잠든 사람이 알아차려야 하므로 중단이 아니라 알람을 바로 울린다.
     * 휴식 중(Resting)에만 동작한다. 이미 알람이 울리는 중이면 무시.
     */
    fun wakeUpEarly(reason: StopReason) {
        val current = _state.value as? NapState.Resting ?: return
        restTimerJob?.cancel()
        restTimerJob = scope.launch { ringAlarmUntilAwake(current.session, earlyWakeReason = reason) }
        logger.log("조기 기상: $reason")
    }

    /** 차량 감시가 알린 변화 처리 (설계서 S-05, S-07) */
    fun handleVehicleAlert(alert: VehicleAlert) {
        logger.log("차량 감시 알림: $alert")
        when (alert) {
            VehicleAlert.DOOR_UNLOCKED -> stopNapNow(StopReason.DOOR_UNLOCKED)
            VehicleAlert.DOOR_OPENED -> stopNapNow(StopReason.DOOR_OPENED)
            VehicleAlert.BRAKE_PRESSED -> stopNapNow(StopReason.BRAKE_PRESSED)
            VehicleAlert.PARKING_RELEASED -> stopNapNow(StopReason.PARKING_RELEASED)
            VehicleAlert.BATTERY_LOW -> wakeUpEarly(StopReason.BATTERY_LOW)
        }
    }

    /** 중단 화면의 "원래대로 되돌리기" */
    fun restoreVehicleAfterStop() {
        val stopped = _state.value as? NapState.Stopped ?: return
        scope.launch {
            val results = runSafely("중단 후 복원") { restEnvironment.restoreOriginalState() }.orEmpty()
            val latest = _state.value
            if (latest is NapState.Stopped && latest.session.id == stopped.session.id) {
                _state.value = latest.copy(restoreResults = results)
            }
        }
    }

    /**
     * 휴식 마치기: 기록을 저장하고 완료 상태로 간다.
     * 대나무숲 삭제/보관 확인은 화면에서 이 함수를 부르기 전에 끝낸다.
     */
    suspend fun finishRest(): RestRecord? {
        val current = _state.value
        val record = when (current) {
            is NapState.AwakeCare -> buildRecord(current.session, current.awakeConfirmedAtMillis, stopReason = null)
            is NapState.Stopped -> buildRecord(current.session, current.stoppedAtMillis, current.reason)
            else -> return null
        }
        vehicleMonitor.stopWatching()
        historyRepository.addRecord(record)
        sessionRepository.clearActiveSession()
        _environmentResults.value = emptyList()
        keepAlive.stopKeepingAlive()
        _state.value = NapState.Completed(record)
        logger.log("휴식 완료 기록 저장: 정상 종료=${record.endedNormally}")
        return record
    }

    /** 완료 화면에서 홈으로 */
    fun returnToIdle() {
        if (_state.value is NapState.Completed) _state.value = NapState.Idle
    }

    // ------------------------------------------------------------------
    // 앱을 떠날 때 · 다시 켤 때 (11/11 사용자 결정)
    // 끝난 휴식 화면(알람·깨어남·중단·완료)으로 다시 돌아오지 않고, 다음에는 처음 화면부터 시작한다.
    // 단, 쉬는 중(Resting)에는 정리하지 않는다 — 다른 앱을 잠깐 봐도 알람은 제때 울려야 하므로.
    // ------------------------------------------------------------------

    private val _interruptedNotice = MutableStateFlow<RestRecord?>(null)

    /** 앱이 휴식 도중 꺼져서, 다시 켤 때 정리한 기록 (홈에서 한 번 알려주고 지운다) */
    val interruptedNotice: StateFlow<RestRecord?> = _interruptedNotice.asStateFlow()

    fun consumeInterruptedNotice() {
        _interruptedNotice.value = null
    }

    /**
     * 사용자가 휴식 화면을 떠났을 때(다른 앱으로 이동·앱 닫기) 부른다.
     *  - 쉬는 중이면 아무것도 하지 않는다(false).
     *  - 알람 중이면 깨어난 것으로 보고 알람을 끄고 원래대로 되돌린다.
     *  - 중단 화면이면 차를 원래대로 되돌린다.
     *  - 고르지 않은 대나무숲 이야기는 지우지 않고 보관한다(사용자 글을 말없이 지우지 않기 위해).
     *  - 기록을 저장하고 처음 상태(Idle)로 돌아간다(true).
     */
    suspend fun closeAfterUserLeft(): Boolean {
        when (val current = _state.value) {
            NapState.Idle, is NapState.Resting -> return false
            is NapState.Completed -> {
                returnToIdle()
                return true
            }
            is NapState.AlarmRinging -> confirmAwake()
            is NapState.Stopped -> runSafely("떠날 때 복원") { restEnvironment.restoreOriginalState() }
            is NapState.AwakeCare -> Unit
        }
        val session = _state.value.activeSessionOrNull ?: return false
        keepUndecidedBambooNote(session)
        finishRest()
        returnToIdle()
        logger.log("앱을 떠나서 휴식을 정리하고 처음 상태로")
        return true
    }

    /**
     * 앱을 새로 켤 때 부른다. 지난번에 앱이 휴식 도중 꺼졌으면(비정상 종료 등) 그 휴식을 이어가지 않고
     * 차를 원래대로 되돌린 뒤 "중간에 끊김"으로 기록하고 처음 화면부터 시작한다.
     */
    suspend fun closeInterruptedSessionOnLaunch(): RestRecord? {
        if (_state.value != NapState.Idle) return null
        val session = sessionRepository.loadActiveSession() ?: return null
        val now = timeSource.nowMillis()
        if (!isTrustworthySavedSession(session, now)) {
            sessionRepository.clearActiveSession()
            logger.log("저장된 휴식 정보가 규칙에 맞지 않아 무시함")
            return null
        }
        runSafely("다시 켤 때 복원") { restEnvironment.restoreOriginalState() }
        keepUndecidedBambooNote(session)
        val record = buildRecord(session, now, StopReason.APP_INTERRUPTED)
        historyRepository.addRecord(record)
        sessionRepository.clearActiveSession()
        keepAlive.stopKeepingAlive()
        _interruptedNotice.value = record
        logger.log("지난 휴식이 중간에 끊겨 정리함")
        return record
    }

    private suspend fun keepUndecidedBambooNote(session: NapSession) {
        val noteId = session.bambooNoteId ?: return
        val note = bambooRepository.findNote(noteId) ?: return
        if (!note.isKept) bambooRepository.markNoteAsKept(noteId)
    }

    // ------------------------------------------------------------------
    // 앱 재실행 복구 (T-14) — 11/11부터 앱 시작 때는 closeInterruptedSessionOnLaunch 를 쓴다.
    // 아래 함수는 "이어가기" 방식이 다시 필요할 때를 위해 남겨 둔다(테스트 포함).
    // ------------------------------------------------------------------

    /**
     * 앱이 휴식 중에 꺼졌다 다시 켜졌을 때 부른다.
     * - 아직 종료 시각 전이면: 휴식을 이어간다 (이미 지난 깨우기 단계는 건너뜀)
     * - 종료 시각이 지났으면: "앱이 중간에 종료됨"으로 중단 처리 → 사용자가 마치기
     */
    suspend fun resumeUnfinishedSession() {
        if (_state.value != NapState.Idle) return
        val session = sessionRepository.loadActiveSession() ?: return
        val now = timeSource.nowMillis()
        if (!isTrustworthySavedSession(session, now)) {
            // 저장 파일이 조작됐거나 규칙이 바뀌어 맞지 않는 값 → 이어가지 않고 버린다 (보안 D)
            sessionRepository.clearActiveSession()
            logger.log("저장된 휴식 정보가 규칙에 맞지 않아 무시함")
            return
        }
        if (now < session.endsAtMillis) {
            keepAlive.startKeepingAlive(session.endsAtMillis)
            _state.value = NapState.Resting(session, wakeGuidance = null)
            logger.log("끝나지 않은 휴식 이어가기")
            startWatchingVehicle()
            startRestTimer(session)
        } else {
            _state.value = NapState.Stopped(session, StopReason.APP_INTERRUPTED, stoppedAtMillis = now)
            logger.log("종료 시각이 지난 휴식 → 중단 처리")
        }
    }

    // ------------------------------------------------------------------
    // 내부 도우미
    // ------------------------------------------------------------------

    /**
     * 저장된 휴식 정보가 믿을 만한지: 길이가 허용 범위 안이고, 시작·종료 시각이 길이와 맞고, 미래에 시작하지 않았는지.
     */
    private fun isTrustworthySavedSession(session: NapSession, now: Long): Boolean {
        val planned = session.plannedDurationMillis
        val isAllowedLength = if (session.isTestRun) {
            planned in 1..rules.maxTestRunMillis
        } else {
            planned in rules.minRestMillis..rules.maxRestMillis
        }
        return isAllowedLength &&
            session.endsAtMillis - session.startedAtMillis == planned &&
            session.startedAtMillis <= now + SafetyRules.MINUTE
    }

    private fun startWatchingVehicle() {
        vehicleMonitor.startWatching { alert -> handleVehicleAlert(alert) }
    }

    private fun cancelTimerAndAlarm() {
        prepareJob?.cancel() // 세팅 중(등받이 이동 등)이면 멈춘다. SeatMover 가 finally 에서 STOP
        prepareJob = null
        restTimerJob?.cancel()
        restTimerJob = null
        alarmSound.stopRinging()
    }

    private fun buildRecord(session: NapSession, endedAtMillis: Long, stopReason: StopReason?): RestRecord {
        val restEndMillis = minOf(endedAtMillis, session.endsAtMillis)
        return RestRecord(
            id = UUID.randomUUID().toString(),
            presetName = session.presetName,
            plannedDurationMillis = session.plannedDurationMillis,
            startedAtMillis = session.startedAtMillis,
            endedAtMillis = timeSource.nowMillis(),
            actualRestMillis = (restEndMillis - session.startedAtMillis).coerceAtLeast(0),
            endedNormally = stopReason == null,
            stopReason = stopReason,
            isTestRun = session.isTestRun,
        )
    }

    /** 차량 제어 실패가 휴식 흐름 전체를 멈추지 않게 감싼다. 취소는 그대로 전달한다. */
    private suspend fun <T> runSafely(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log("$label 실패: ${e.javaClass.simpleName}")
        null
    }
}
