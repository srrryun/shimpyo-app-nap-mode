package com.example.hyundaitest.vehicle

import com.example.hyundaitest.safety.SafetyRules
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sign

/** 등받이를 움직이는 방향 (차량 SDK 에 묶이지 않게 앱 쪽 이름으로 둔다) */
enum class SeatDirection { RECLINE, RAISE }

/**
 * 등받이를 실제로 움직이는 부품의 약속. Pleos 구현은 PleosSeatActuator, 테스트는 가짜.
 * SDK 에 "위치로 이동" 함수가 없어서 "움직이기 시작 / 멈추기 / 위치 읽기"만 있다.
 */
interface SeatActuator {
    suspend fun readPosition(): Int?
    suspend fun startMoving(direction: SeatDirection): Boolean
    /** 반드시 멈추기 요청 (기다리지 않음). 실패해도 예외를 던지지 않는다. */
    fun requestStop()
}

/**
 * 등받이를 안전하게 움직인다 (설계서 S-03).
 *  - 한 번 움직임은 최대 seatMoveStepMaxMillis(2초), 그 뒤 반드시 STOP
 *  - 전체 움직임은 최대 seatMoveTotalMaxMillis(15초)
 *  - 움직이는 도중 취소되어도(중단 버튼 등) finally 에서 STOP
 *  - 단계마다 canKeepMoving()(예: 아직 주차 중인가)을 확인하고, false 면 멈춘다
 */
class SeatMover(
    private val actuator: SeatActuator,
    private val rules: SafetyRules,
    private val canKeepMoving: suspend () -> Boolean = { true },
) {
    /** 눕힐 때 위치 값이 커지는지(+1) 작아지는지(-1). 위치가 실제로 바뀌는 차에서만 알 수 있다. */
    var reclineSign: Int? = null
        private set

    /** 정해진 시간만큼 한 방향으로 움직인다. 반환: 실제로 움직인 시간(ms) */
    suspend fun moveFor(direction: SeatDirection, totalMillis: Long): Long {
        val limit = totalMillis.coerceAtMost(rules.seatMoveTotalMaxMillis)
        var moved = 0L
        while (moved < limit) {
            if (!canKeepMoving()) break
            val before = actuator.readPosition()
            val step = minOf(rules.seatMoveStepMaxMillis, limit - moved)
            if (!moveOneStep(direction, step)) break
            moved += step
            learnDirection(direction, before, actuator.readPosition())
        }
        return moved
    }

    /**
     * 목표 위치까지 움직인다. 방향을 모르거나(에뮬레이터처럼 위치가 안 바뀜) 위치를 못 읽으면 false.
     * 목표를 지나치면 그 자리에서 멈춘다.
     */
    suspend fun moveTo(target: Int): Boolean {
        val sign = reclineSign ?: return false
        var moved = 0L
        while (moved < rules.seatMoveTotalMaxMillis) {
            if (!canKeepMoving()) return false
            val now = actuator.readPosition() ?: return false
            val needed = (target - now).sign
            if (needed == 0) return true
            val direction = if (needed == sign) SeatDirection.RECLINE else SeatDirection.RAISE
            val step = minOf(FINE_STEP_MILLIS, rules.seatMoveTotalMaxMillis - moved)
            if (!moveOneStep(direction, step)) return false
            moved += step
            val after = actuator.readPosition() ?: return false
            if ((target - after).sign != needed) return true // 목표에 닿았거나 지나침
        }
        return false
    }

    /** 즉시 멈춤 (S-05) */
    fun stopNow() = actuator.requestStop()

    private suspend fun moveOneStep(direction: SeatDirection, stepMillis: Long): Boolean {
        try {
            if (!actuator.startMoving(direction)) return false
            delay(stepMillis)
            return true
        } finally {
            withContext(NonCancellable) { actuator.requestStop() }
        }
    }

    private fun learnDirection(direction: SeatDirection, before: Int?, after: Int?) {
        if (before == null || after == null || before == after) return
        val change = (after - before).sign
        reclineSign = if (direction == SeatDirection.RECLINE) change else -change
    }

    private companion object {
        /** 목표 위치를 맞출 때는 짧게 움직이며 확인한다 */
        const val FINE_STEP_MILLIS = 500L
    }
}
