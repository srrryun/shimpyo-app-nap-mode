package com.example.hyundaitest.safety

import com.example.hyundaitest.nap.NapStartRequest

/**
 * 안전 관문 — 휴식 시작과(2단계부터) 모든 차량 제어 요청이 이곳을 거친다. (설계서 8장)
 *
 * 1단계: 휴식 시작 조건만 검사한다.
 * 2단계: 차량 제어 허용 여부(정차·승인 항목·시간 제한) 검사를 이 인터페이스에 추가한다.
 */
interface SafetyGuard {
    fun checkBeforeStart(request: NapStartRequest, isRestInProgress: Boolean): SafetyDecision
}

/** 안전 관문의 판단 결과 */
sealed class SafetyDecision {
    data object Allowed : SafetyDecision()
    data class Blocked(val reasons: List<BlockReason>) : SafetyDecision()
}

/** 시작을 막은 이유. message 는 화면에 그대로 보여준다. */
enum class BlockReason(val message: String) {
    /** 주차 상태를 자동으로 읽었는데 주차가 아님 — 사용자가 눌러도 시작하지 않는다 */
    NOT_PARKED("P단에 두고 파킹브레이크를 걸어 주세요. 주차 상태일 때만 쉴 수 있어요."),
    /** 자동으로 읽지 못했고, 사용자도 정차를 확인하지 않음 */
    NOT_CONFIRMED_PARKED("차가 안전하게 정차해 있는지 확인해 주세요."),
    DURATION_OUT_OF_RANGE("휴식 시간이 허용 범위를 벗어났어요."),
    ALREADY_RESTING("이미 휴식이 진행 중이에요."),
}

/** 1단계 기본 구현 */
class BasicSafetyGuard(private val rules: SafetyRules) : SafetyGuard {

    override fun checkBeforeStart(request: NapStartRequest, isRestInProgress: Boolean): SafetyDecision {
        val reasons = mutableListOf<BlockReason>()

        if (isRestInProgress) reasons += BlockReason.ALREADY_RESTING

        parkingBlockReason(request)?.let { reasons += it }

        if (!isDurationAllowed(request)) reasons += BlockReason.DURATION_OUT_OF_RANGE

        return if (reasons.isEmpty()) SafetyDecision.Allowed else SafetyDecision.Blocked(reasons)
    }

    /**
     * S-01 정차 확인.
     * 자동으로 읽은 값이 있으면 그것을 따르고, 못 읽었을 때만 사용자의 한 번 확인을 받는다.
     * 자동으로 "주차 아님"이 읽히면 사용자가 확인했어도 막는다.
     */
    private fun parkingBlockReason(request: NapStartRequest): BlockReason? = when (request.parkingState) {
        ParkingState.PARKED -> null
        ParkingState.NOT_PARKED -> BlockReason.NOT_PARKED
        ParkingState.UNKNOWN -> if (request.isParkedConfirmedByUser) null else BlockReason.NOT_CONFIRMED_PARKED
    }

    private fun isDurationAllowed(request: NapStartRequest): Boolean {
        val duration = request.plan.durationMillis
        return if (request.plan.isTestRun) {
            duration in 1..rules.maxTestRunMillis
        } else {
            duration in rules.minRestMillis..rules.maxRestMillis
        }
    }
}
