package com.example.hyundaitest.data.model

/**
 * 끝난 휴식 한 번의 기록. 고민 내용은 넣지 않는다 (설계서 6장 · 9.2).
 * Room 전환 시 이 모양 그대로 표(entity)로 옮길 수 있게 단순한 값만 둔다.
 */
data class RestRecord(
    val id: String,
    val presetName: String,
    val plannedDurationMillis: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** 실제로 쉰 시간 = (기상 시각 또는 중단 시각, 예정 종료 시각 중 이른 쪽) - 시작 시각 */
    val actualRestMillis: Long,
    /** true = 정상 종료(깨어났어요), false = 중단 */
    val endedNormally: Boolean,
    /** 중단된 경우의 이유. 정상 종료면 null */
    val stopReason: StopReason?,
    val isTestRun: Boolean,
)
