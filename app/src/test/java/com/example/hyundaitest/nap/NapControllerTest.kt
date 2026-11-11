package com.example.hyundaitest.nap

import com.example.hyundaitest.data.BambooRepository
import com.example.hyundaitest.data.NapSessionRepository
import com.example.hyundaitest.data.RestHistoryRepository
import com.example.hyundaitest.data.model.BambooNote
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestRecord
import com.example.hyundaitest.data.model.StopReason
import com.example.hyundaitest.safety.BasicSafetyGuard
import com.example.hyundaitest.safety.BlockReason
import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.ComfortItem
import com.example.hyundaitest.vehicle.ControlResult
import com.example.hyundaitest.vehicle.RestEnvironment
import com.example.hyundaitest.vehicle.VehicleAlert
import com.example.hyundaitest.vehicle.VehicleMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NapController 단위 테스트 (설계서 14장 T-01, T-02, T-04, T-05, T-09, T-10, T-14 의 로직 부분).
 * 가짜 시계(테스트 스케줄러)로 시간을 빨리 감아서 1분 휴식을 즉시 검증한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NapControllerTest {

    private val minute = SafetyRules.MINUTE
    private val second = SafetyRules.SECOND

    // ------------------------------------------------------------------
    // 시작 조건 (SafetyGuard)
    // ------------------------------------------------------------------

    @Test
    fun startNap_isBlocked_whenParkingNotConfirmed() = runTest {
        val app = TestApp(this)
        val result = app.controller.startNap(oneMinuteTestRequest(isParked = false))

        assertTrue(result is StartNapResult.Blocked)
        assertEquals(listOf(BlockReason.NOT_CONFIRMED_PARKED), (result as StartNapResult.Blocked).reasons)
        assertEquals(NapState.Idle, app.controller.state.value)
        assertNull(app.sessions.saved)
    }

    @Test
    fun startNap_isBlocked_whenParkingBrakeReleased_evenIfUserConfirmed() = runTest {
        val app = TestApp(this)
        val result = app.controller.startNap(
            oneMinuteTestRequest(isParked = true, parkingState = ParkingState.NOT_PARKED)
        )

        assertEquals(listOf(BlockReason.NOT_PARKED), (result as StartNapResult.Blocked).reasons)
        assertEquals(NapState.Idle, app.controller.state.value)
    }

    @Test
    fun startNap_starts_withoutUserConfirm_whenParkingDetected() = runTest {
        val app = TestApp(this)
        val result = app.controller.startNap(
            oneMinuteTestRequest(isParked = false, parkingState = ParkingState.PARKED)
        )

        assertTrue(result is StartNapResult.Started)
    }

    @Test
    fun startNap_isBlocked_whenPresetDurationOutOfRange() = runTest {
        val app = TestApp(this)
        val tooShort = NapStartRequest(NapPlan("너무 짧은 휴식", 3 * minute), bambooText = null, isParkedConfirmedByUser = true)

        val result = app.controller.startNap(tooShort)

        assertEquals(listOf(BlockReason.DURATION_OUT_OF_RANGE), (result as StartNapResult.Blocked).reasons)
    }

    @Test
    fun startNap_isBlocked_whenAlreadyResting() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())

        val secondStart = app.controller.startNap(oneMinuteTestRequest())

        assertTrue((secondStart as StartNapResult.Blocked).reasons.contains(BlockReason.ALREADY_RESTING))
    }

    // ------------------------------------------------------------------
    // 전체 흐름
    // ------------------------------------------------------------------

    @Test
    fun fullRest_goesRestingThenAlarmThenAwakeCareThenCompleted() = runTest {
        val app = TestApp(this)

        app.controller.startNap(oneMinuteTestRequest())
        assertTrue(app.controller.state.value is NapState.Resting)
        assertTrue(app.keepAlive.isAlive)
        assertNotNull(app.sessions.saved)

        advanceTimeBy(1 * minute)
        runCurrent()
        val ringing = app.controller.state.value as NapState.AlarmRinging
        assertEquals(1, ringing.ringNumber)
        assertTrue(app.alarm.isRinging)

        app.controller.confirmAwake()
        runCurrent()
        assertTrue(app.controller.state.value is NapState.AwakeCare)
        assertFalse(app.alarm.isRinging)
        assertEquals(1, app.environment.restoreCount)

        val record = app.controller.finishRest()!!
        assertTrue(record.endedNormally)
        assertEquals(1 * minute, record.actualRestMillis)
        assertEquals(1, app.history.records.size)
        assertNull(app.sessions.saved)
        assertFalse(app.keepAlive.isAlive)
        assertTrue(app.controller.state.value is NapState.Completed)

        app.controller.returnToIdle()
        assertEquals(NapState.Idle, app.controller.state.value)
    }

    @Test
    fun wakeSteps_runInOrderBeforeEnd_scaledForShortRest() = runTest {
        val app = TestApp(this, vehicleControlEnabled = true)
        app.controller.startNap(oneMinuteTestRequest())

        // 1분 휴식이면 5분 전 → 30초 전, 3분 전 → 18초 전, 2분 전 → 12초 전
        advanceTimeBy(30 * second)
        runCurrent()
        assertEquals("조명을 서서히 밝히고 있어요.", (app.controller.state.value as NapState.Resting).wakeGuidance)
        assertEquals(listOf("light_up"), app.environment.wakeCalls)

        advanceTimeBy(18 * second)
        runCurrent()
        assertEquals(listOf("light_up", "fresh_air", "raise_seat"), app.environment.wakeCalls)
    }

    @Test
    fun wakeGuidance_doesNotClaimVehicleControl_whenControlDisabled() = runTest {
        val app = TestApp(this, vehicleControlEnabled = false)
        app.controller.startNap(oneMinuteTestRequest())

        advanceTimeBy(30 * second)
        runCurrent()

        val guidance = (app.controller.state.value as NapState.Resting).wakeGuidance
        assertFalse(guidance!!.contains("조명"))
    }

    // ------------------------------------------------------------------
    // 알람 미응답 규칙 (T-09)
    // ------------------------------------------------------------------

    @Test
    fun alarm_givesUpAfterMaxRings_andKeepsWaitingForUser() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())

        advanceTimeBy(1 * minute + 5 * minute)
        runCurrent()

        val ringing = app.controller.state.value as NapState.AlarmRinging
        assertTrue(ringing.hasGivenUp)
        assertEquals(5, app.alarm.startCount)
        assertFalse(app.alarm.isRinging)

        app.controller.confirmAwake() // 알람을 멈춘 뒤에도 "깨어났어요"는 받는다
        assertTrue(app.controller.state.value is NapState.AwakeCare)
    }

    // ------------------------------------------------------------------
    // 즉시 중단 (T-05)
    // ------------------------------------------------------------------

    @Test
    fun stopNapNow_stopsMovementAndTimer_andRecordsStop() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())
        advanceTimeBy(10 * second)
        runCurrent()

        app.controller.stopNapNow(StopReason.USER_PRESSED_STOP)

        val stopped = app.controller.state.value as NapState.Stopped
        assertEquals(StopReason.USER_PRESSED_STOP, stopped.reason)
        assertEquals(1, app.environment.stopAllCount)

        advanceTimeBy(2 * minute)
        runCurrent()
        assertEquals("중단 후에는 알람이 울리면 안 된다", 0, app.alarm.startCount)

        val record = app.controller.finishRest()!!
        assertFalse(record.endedNormally)
        assertEquals(StopReason.USER_PRESSED_STOP, record.stopReason)
        assertEquals(10 * second, record.actualRestMillis)
    }

    // ------------------------------------------------------------------
    // 대나무숲 연결
    // ------------------------------------------------------------------

    @Test
    fun bambooText_isTrimmedSavedAndLinkedToSession() = runTest {
        val app = TestApp(this)
        val result = app.controller.startNap(oneMinuteTestRequest(bambooText = "  오늘 회의가 걱정돼요  "))

        val session = (result as StartNapResult.Started).session
        assertEquals(1, app.bamboo.notes.size)
        assertEquals("오늘 회의가 걱정돼요", app.bamboo.notes.first().text)
        assertEquals(app.bamboo.notes.first().id, session.bambooNoteId)
    }

    @Test
    fun blankBambooText_isNotSaved() = runTest {
        val app = TestApp(this)
        val result = app.controller.startNap(oneMinuteTestRequest(bambooText = "   "))

        assertNull((result as StartNapResult.Started).session.bambooNoteId)
        assertTrue(app.bamboo.notes.isEmpty())
    }

    // ------------------------------------------------------------------
    // 앱 재실행 복구 (T-14)
    // ------------------------------------------------------------------

    @Test
    fun resumeUnfinishedSession_continuesRest_whenNotEnded() = runTest {
        val app = TestApp(this)
        app.sessions.saved = savedSession(endsAtMillis = 1 * minute)
        advanceTimeBy(10 * second) // 앱이 10초 뒤 다시 켜졌다고 가정

        app.controller.resumeUnfinishedSession()
        assertTrue(app.controller.state.value is NapState.Resting)

        advanceTimeBy(50 * second)
        runCurrent()
        assertTrue(app.controller.state.value is NapState.AlarmRinging)
    }

    @Test
    fun resumeUnfinishedSession_marksInterrupted_whenAlreadyEnded() = runTest {
        val app = TestApp(this)
        app.sessions.saved = savedSession(endsAtMillis = 1 * minute)
        advanceTimeBy(2 * minute)

        app.controller.resumeUnfinishedSession()

        val stopped = app.controller.state.value as NapState.Stopped
        assertEquals(StopReason.APP_INTERRUPTED, stopped.reason)
        assertEquals(0, app.alarm.startCount)
    }

    // ------------------------------------------------------------------
    // 2단계: 차량 감시 · 휴식 환경 · 저장값 검사
    // ------------------------------------------------------------------

    @Test
    fun vehicleMonitor_watchesOnlyWhileResting() = runTest {
        val app = TestApp(this)
        assertFalse(app.monitor.isWatching)

        app.controller.startNap(oneMinuteTestRequest())
        assertTrue(app.monitor.isWatching)

        advanceTimeBy(1 * minute + second)
        runCurrent()
        app.controller.confirmAwake()
        assertFalse("깨어난 뒤에는 감시를 멈춘다", app.monitor.isWatching)
    }

    @Test
    fun doorUnlocked_duringRest_stopsRestAndMovement() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())
        runCurrent()

        app.monitor.fire(VehicleAlert.DOOR_UNLOCKED)

        val stopped = app.controller.state.value as NapState.Stopped
        assertEquals(StopReason.DOOR_UNLOCKED, stopped.reason)
        assertEquals(1, app.environment.stopAllCount)
        assertFalse(app.monitor.isWatching)
    }

    @Test
    fun doorOpened_duringRest_stopsRest() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())

        app.monitor.fire(VehicleAlert.DOOR_OPENED)

        assertEquals(StopReason.DOOR_OPENED, (app.controller.state.value as NapState.Stopped).reason)
    }

    @Test
    fun parkingReleased_duringRest_stopsRest() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())

        app.monitor.fire(VehicleAlert.PARKING_RELEASED)

        assertEquals(StopReason.PARKING_RELEASED, (app.controller.state.value as NapState.Stopped).reason)
    }

    @Test
    fun batteryLow_duringRest_ringsAlarmEarly_insteadOfStopping() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())
        advanceTimeBy(10 * second)
        runCurrent()

        app.monitor.fire(VehicleAlert.BATTERY_LOW)
        runCurrent()

        val alarm = app.controller.state.value as NapState.AlarmRinging
        assertEquals(StopReason.BATTERY_LOW, alarm.earlyWakeReason)
        assertEquals(1, app.alarm.startCount)
    }

    @Test
    fun approvedComfortItems_arePassedToEnvironment() = runTest {
        val app = TestApp(this)
        val approved = setOf(ComfortItem.AMBIENT_LIGHT)
        app.controller.startNap(
            NapStartRequest(
                NapPlan.testRun(1 * minute, approvedComfortItems = approved),
                bambooText = null,
                isParkedConfirmedByUser = true,
            )
        )
        runCurrent()

        assertEquals(approved, app.environment.lastApprovedItems)
    }

    @Test
    fun resumeUnfinishedSession_ignoresTamperedSession() = runTest {
        val app = TestApp(this)
        // 테스트 휴식인데 길이가 2시간 → 저장 파일이 조작된 것으로 본다
        app.sessions.saved = savedSession(endsAtMillis = 120 * minute)

        app.controller.resumeUnfinishedSession()

        assertEquals(NapState.Idle, app.controller.state.value)
        assertNull(app.sessions.saved)
        assertFalse(app.monitor.isWatching)
    }

    // ------------------------------------------------------------------
    // 앱을 떠날 때 · 다시 켤 때 (11/11)
    // ------------------------------------------------------------------

    @Test
    fun closeAfterUserLeft_duringRest_keepsResting() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())

        assertFalse(app.controller.closeAfterUserLeft())
        assertTrue(app.controller.state.value is NapState.Resting)
    }

    @Test
    fun closeAfterUserLeft_duringAlarm_stopsAlarmSavesRecordAndKeepsNote() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest(bambooText = "회의 걱정"))
        advanceTimeBy(1 * minute + second)
        runCurrent()
        assertTrue(app.controller.state.value is NapState.AlarmRinging)

        assertTrue(app.controller.closeAfterUserLeft())
        runCurrent()

        assertEquals(NapState.Idle, app.controller.state.value)
        assertFalse(app.alarm.isRinging)
        assertEquals(1, app.history.records.size)
        assertNull(app.sessions.saved)
        assertTrue("고르지 않은 이야기는 지우지 않고 보관", app.bamboo.notes.single().isKept)
    }

    @Test
    fun closeAfterUserLeft_whenStopped_restoresVehicle() = runTest {
        val app = TestApp(this)
        app.controller.startNap(oneMinuteTestRequest())
        app.controller.stopNapNow(StopReason.USER_PRESSED_STOP)

        assertTrue(app.controller.closeAfterUserLeft())

        assertEquals(1, app.environment.restoreCount)
        assertEquals(NapState.Idle, app.controller.state.value)
    }

    @Test
    fun closeInterruptedSessionOnLaunch_recordsAndStartsFromHome() = runTest {
        val app = TestApp(this)
        app.sessions.saved = savedSession(endsAtMillis = 1 * minute)
        advanceTimeBy(10 * second)

        val record = app.controller.closeInterruptedSessionOnLaunch()

        assertNotNull(record)
        assertEquals(StopReason.APP_INTERRUPTED, record!!.stopReason)
        assertEquals(NapState.Idle, app.controller.state.value)
        assertNull(app.sessions.saved)
        assertEquals(1, app.environment.restoreCount)
        assertEquals(record, app.controller.interruptedNotice.value)
    }

    // ------------------------------------------------------------------
    // 안전 규칙
    // ------------------------------------------------------------------

    @Test
    fun safetyRules_clampRestMinutes_toAllowedRange() {
        val rules = SafetyRules()
        assertEquals(5, rules.clampRestMinutes(0))
        assertEquals(30, rules.clampRestMinutes(90))
        assertEquals(23, rules.clampRestMinutes(23)) // 1분 단위
        assertTrue(rules.isAllowedRestMinutes(23))
        assertFalse(rules.isAllowedRestMinutes(31))
    }

    // ------------------------------------------------------------------
    // 테스트 도우미
    // ------------------------------------------------------------------

    private fun oneMinuteTestRequest(
        isParked: Boolean = true,
        bambooText: String? = null,
        parkingState: ParkingState = ParkingState.UNKNOWN,
    ) = NapStartRequest(
        NapPlan.testRun(1 * minute),
        bambooText = bambooText,
        isParkedConfirmedByUser = isParked,
        parkingState = parkingState,
    )

    private fun savedSession(endsAtMillis: Long) = NapSession(
        id = "saved-session",
        presetName = "테스트 휴식",
        plannedDurationMillis = endsAtMillis,
        startedAtMillis = 0,
        endsAtMillis = endsAtMillis,
        bambooNoteId = null,
        isTestRun = true,
    )

    /** 가짜 부품으로 조립한 NapController */
    private class TestApp(testScope: TestScope, vehicleControlEnabled: Boolean = false) {
        val alarm = FakeAlarmSound()
        val keepAlive = FakeKeepAlive()
        val environment = FakeRestEnvironment(vehicleControlEnabled)
        val sessions = FakeSessionRepository()
        val history = FakeHistoryRepository()
        val bamboo = FakeBambooRepository()
        val monitor = FakeVehicleMonitor()
        private val rules = SafetyRules()

        val controller = NapController(
            scope = testScope.backgroundScope,
            timeSource = TimeSource { testScope.testScheduler.currentTime },
            rules = rules,
            safetyGuard = BasicSafetyGuard(rules),
            alarmSound = alarm,
            keepAlive = keepAlive,
            restEnvironment = environment,
            sessionRepository = sessions,
            historyRepository = history,
            bambooRepository = bamboo,
            vehicleMonitor = monitor,
        )
    }
}

private class FakeAlarmSound : AlarmSound {
    var isRinging = false
    var startCount = 0

    override fun startRinging() {
        isRinging = true
        startCount++
    }

    override fun stopRinging() {
        isRinging = false
    }
}

private class FakeKeepAlive : RestKeepAlive {
    var isAlive = false

    override fun startKeepingAlive(endsAtMillis: Long) {
        isAlive = true
    }

    override fun stopKeepingAlive() {
        isAlive = false
    }
}

private class FakeRestEnvironment(override val isVehicleControlEnabled: Boolean) : RestEnvironment {
    val wakeCalls = mutableListOf<String>()
    var prepareCount = 0
    var restoreCount = 0
    var stopAllCount = 0

    var lastApprovedItems: Set<ComfortItem>? = null

    override suspend fun prepareForRest(session: NapSession, approvedItems: Set<ComfortItem>): List<ControlResult> {
        prepareCount++
        lastApprovedItems = approvedItems
        return emptyList()
    }

    override suspend fun wakeStepLightUp() {
        wakeCalls += "light_up"
    }

    override suspend fun wakeStepFreshAir() {
        wakeCalls += "fresh_air"
    }

    override suspend fun wakeStepRaiseSeat() {
        wakeCalls += "raise_seat"
    }

    override suspend fun restoreOriginalState(): List<ControlResult> {
        restoreCount++
        return emptyList()
    }

    override fun stopAllMovementNow() {
        stopAllCount++
    }
}

private class FakeSessionRepository : NapSessionRepository {
    var saved: NapSession? = null

    override suspend fun saveActiveSession(session: NapSession) {
        saved = session
    }

    override suspend fun loadActiveSession(): NapSession? = saved

    override suspend fun clearActiveSession() {
        saved = null
    }
}

private class FakeHistoryRepository : RestHistoryRepository {
    val records = mutableListOf<RestRecord>()

    override suspend fun addRecord(record: RestRecord) {
        records += record
    }

    override suspend fun loadRecords(): List<RestRecord> = records.sortedByDescending { it.endedAtMillis }

    override suspend fun loadLatestRecord(): RestRecord? = loadRecords().firstOrNull()
}

private class FakeBambooRepository : BambooRepository {
    override suspend fun countUnencryptedNotes(): Int = 0

    val notes = mutableListOf<BambooNote>()

    override suspend fun saveNewNote(text: String, createdAtMillis: Long): BambooNote =
        BambooNote(id = "note-${notes.size + 1}", text = text, createdAtMillis = createdAtMillis, isKept = false)
            .also { notes += it }

    override suspend fun findNote(noteId: String): BambooNote? = notes.firstOrNull { it.id == noteId }

    override suspend fun loadKeptNotes(): List<BambooNote> = notes.filter { it.isKept }

    override suspend fun markNoteAsKept(noteId: String) {
        val index = notes.indexOfFirst { it.id == noteId }
        if (index >= 0) notes[index] = notes[index].copy(isKept = true)
    }

    override suspend fun deleteNote(noteId: String) {
        notes.removeAll { it.id == noteId }
    }
}

private class FakeVehicleMonitor : VehicleMonitor {
    private var onAlert: ((VehicleAlert) -> Unit)? = null
    val isWatching: Boolean get() = onAlert != null

    override fun startWatching(onAlert: (VehicleAlert) -> Unit) {
        this.onAlert = onAlert
    }

    override fun stopWatching() {
        onAlert = null
    }

    /** 차량에서 변화가 감지된 것처럼 알린다 */
    fun fire(alert: VehicleAlert) {
        onAlert?.invoke(alert)
    }
}
