package com.example.hyundaitest.nap

import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestPreset
import com.example.hyundaitest.safety.BlockReason
import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.ComfortItem

/**
 * 이번 휴식의 계획.
 * @param approvedComfortItems 사용자가 시작 전에 승인한 차량 환경 항목. 이 밖의 항목은 제어하지 않는다 (S-02).
 */
data class NapPlan(
    val presetName: String,
    val durationMillis: Long,
    val isTestRun: Boolean = false,
    val approvedComfortItems: Set<ComfortItem> = emptySet(),
) {
    companion object {
        fun fromPreset(preset: RestPreset, approvedComfortItems: Set<ComfortItem> = emptySet()): NapPlan =
            NapPlan(
                presetName = preset.name,
                durationMillis = preset.minutes * SafetyRules.MINUTE,
                approvedComfortItems = approvedComfortItems,
            )

        /** 개발용 짧은 테스트 휴식 */
        fun testRun(
            durationMillis: Long = SafetyRules.MINUTE,
            approvedComfortItems: Set<ComfortItem> = emptySet(),
        ): NapPlan = NapPlan(
            presetName = "테스트 휴식",
            durationMillis = durationMillis,
            isTestRun = true,
            approvedComfortItems = approvedComfortItems,
        )
    }
}

/**
 * 휴식 시작 요청 = 계획 + 대나무숲 글(선택) + 주차 상태
 *
 * @param parkingState 차에서 자동으로 읽은 주차 상태
 * @param isParkedConfirmedByUser 자동으로 읽지 못했을 때(UNKNOWN) 사용자가 "정차 중이에요"를 눌렀는지
 */
data class NapStartRequest(
    val plan: NapPlan,
    val bambooText: String?,
    val isParkedConfirmedByUser: Boolean,
    val parkingState: ParkingState = ParkingState.UNKNOWN,
)

/** startNap 의 결과 */
sealed class StartNapResult {
    data class Started(val session: NapSession) : StartNapResult()
    data class Blocked(val reasons: List<BlockReason>) : StartNapResult()
}
