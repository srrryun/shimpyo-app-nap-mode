package com.example.hyundaitest.nap

import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestRecord
import com.example.hyundaitest.data.model.StopReason
import com.example.hyundaitest.vehicle.ControlResult

/**
 * 휴식의 현재 단계 (설계서 11장 상태 머신).
 *
 *   Idle → Resting → AlarmRinging → AwakeCare → Completed → Idle
 *               └────────┴─────→ Stopped → Completed
 *
 * 화면은 이 상태만 보고 그린다. 상태를 바꾸는 곳은 NapController 하나뿐이다.
 */
sealed class NapState {

    /** 휴식 전 */
    data object Idle : NapState()

    /**
     * 휴식 중.
     * @param wakeGuidance 깨우기 단계가 시작되면 보여줄 안내 문구 (그 전에는 null)
     */
    data class Resting(
        val session: NapSession,
        val wakeGuidance: String?,
    ) : NapState()

    /**
     * 휴식 시간이 끝나 알람이 울리는 중. 사용자가 "깨어났어요"를 눌러야 다음으로 간다.
     * @param hasGivenUp 최대 횟수를 다 울려서 알람을 멈춘 상태 (화면은 유지)
     * @param earlyWakeReason 예정보다 일찍 깨운 이유 (배터리 부족 등). 정상 종료면 null
     */
    data class AlarmRinging(
        val session: NapSession,
        val ringNumber: Int,
        val maxRings: Int,
        val hasGivenUp: Boolean,
        val earlyWakeReason: StopReason? = null,
    ) : NapState()

    /** 깨어남 케어: 사용자가 깨어났음을 확인한 뒤 복귀 안내 · 원래 상태 복원 · 대나무숲 */
    data class AwakeCare(
        val session: NapSession,
        val awakeConfirmedAtMillis: Long,
        /** 원래 상태 복원 결과. 복원 전이거나 차량 제어가 꺼져 있으면 빈 목록 */
        val restoreResults: List<ControlResult> = emptyList(),
    ) : NapState()

    /** 중간에 멈춤 (버튼, 앱 종료, 2단계부터 문 열림·브레이크 등) */
    data class Stopped(
        val session: NapSession,
        val reason: StopReason,
        val stoppedAtMillis: Long,
        val restoreResults: List<ControlResult> = emptyList(),
    ) : NapState()

    /** 휴식 완료. 기록이 저장된 상태 */
    data class Completed(val record: RestRecord) : NapState()

    /** 진행 중인 휴식이면 그 세션, 아니면 null */
    val activeSessionOrNull: NapSession?
        get() = when (this) {
            is Resting -> session
            is AlarmRinging -> session
            is AwakeCare -> session
            is Stopped -> session
            Idle, is Completed -> null
        }

    /** 휴식이 진행 중인지 (새 휴식을 시작하면 안 되는 상태인지) */
    val isRestInProgress: Boolean get() = activeSessionOrNull != null
}
