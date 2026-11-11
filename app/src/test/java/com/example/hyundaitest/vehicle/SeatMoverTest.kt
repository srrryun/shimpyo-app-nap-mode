package com.example.hyundaitest.vehicle

import com.example.hyundaitest.safety.SafetyRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SeatMover 안전 규칙 테스트 (설계서 S-03, T-04).
 * 가짜 등받이와 가상 시계로 "2초씩 · 매번 정지 · 최대 15초 · 주차가 풀리면 멈춤"을 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeatMoverTest {

    private val rules = SafetyRules()

    @Test
    fun moveFor_splitsIntoTwoSecondSteps_andStopsAfterEachStep() = runTest {
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime })
        val mover = SeatMover(seat, rules)

        val moved = mover.moveFor(SeatDirection.RECLINE, 5_000L)

        assertEquals(5_000L, moved)
        assertEquals(listOf(2_000L, 2_000L, 1_000L), seat.stepDurations)
        assertEquals("움직일 때마다 STOP", seat.startCount, seat.stopCount)
        assertFalse(seat.isMoving)
    }

    @Test
    fun moveFor_isCappedAtTotalMaximum() = runTest {
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime })
        val mover = SeatMover(seat, rules)

        val moved = mover.moveFor(SeatDirection.RECLINE, 60_000L)

        assertEquals(rules.seatMoveTotalMaxMillis, moved)
        assertTrue(seat.stepDurations.all { it <= rules.seatMoveStepMaxMillis })
    }

    @Test
    fun moveFor_stopsEarly_whenNoLongerParked() = runTest {
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime })
        var checks = 0
        val mover = SeatMover(seat, rules, canKeepMoving = { checks++ == 0 }) // 첫 단계 뒤 주차가 풀림

        val moved = mover.moveFor(SeatDirection.RECLINE, 6_000L)

        assertEquals(2_000L, moved)
        assertFalse(seat.isMoving)
    }

    @Test
    fun cancelWhileMoving_stillSendsStop() = runTest {
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime })
        val mover = SeatMover(seat, rules)

        val job = launch { mover.moveFor(SeatDirection.RECLINE, 6_000L) }
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(seat.isMoving)

        job.cancel() // 중단 버튼 등
        runCurrent()

        assertFalse("취소돼도 반드시 STOP", seat.isMoving)
    }

    @Test
    fun moveTo_givesUp_whenPositionNeverChanges() = runTest {
        // 에뮬레이터처럼 위치 값이 바뀌지 않는 경우
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime }, positionChangePerStep = 0)
        val mover = SeatMover(seat, rules)
        mover.moveFor(SeatDirection.RECLINE, 2_000L)

        assertFalse(mover.moveTo(10))
        assertFalse(seat.isMoving)
    }

    @Test
    fun moveTo_returnsToOriginalPosition_whenDirectionIsKnown() = runTest {
        val seat = FakeSeatActuator(clock = { testScheduler.currentTime }, positionChangePerStep = 3)
        val mover = SeatMover(seat, rules)
        mover.moveFor(SeatDirection.RECLINE, 4_000L) // 0 → 6, 방향 학습

        assertTrue(mover.moveTo(0))
        assertEquals(0, seat.position)
        assertFalse(seat.isMoving)
    }
}

/** 움직이기 시작할 때마다 position 이 정해진 만큼 바뀌는 가짜 등받이 */
private class FakeSeatActuator(
    private val clock: () -> Long,
    private val positionChangePerStep: Int = 1,
) : SeatActuator {
    var position = 0
    var isMoving = false
    var startCount = 0
    var stopCount = 0
    val stepDurations = mutableListOf<Long>()
    private var startedAt = 0L

    override suspend fun readPosition(): Int = position

    override suspend fun startMoving(direction: SeatDirection): Boolean {
        isMoving = true
        startCount++
        startedAt = clock()
        position += if (direction == SeatDirection.RECLINE) positionChangePerStep else -positionChangePerStep
        return true
    }

    override fun requestStop() {
        if (isMoving) stepDurations += clock() - startedAt
        isMoving = false
        stopCount++
    }
}
