package com.example.hyundaitest.safety

/**
 * 안전·시간 관련 숫자를 한 곳에 모았다. 값을 바꿀 때는 여기만 고치면 된다.
 * (설계서 8장 안전 요구사항, 5장 알람 규칙, 15장 D-2 미정 값)
 */
data class SafetyRules(
    // ----- 휴식 시간 (설계서 4장) -----
    // 프리셋별 범위는 RestPreset.DEFAULTS 에 있다. 여기 값은 전체 안전 한계.
    val minRestMinutes: Int = 5,
    /** 30분을 넘기면 깊은 잠에 들어 깬 뒤 멍한 상태(수면 관성)가 심해진다 */
    val maxRestMinutes: Int = 30,
    /** 화면에서 +/− 한 번에 바뀌는 시간 */
    val restMinutesStep: Int = 1,
    /** 개발용 테스트 휴식의 최대 길이 */
    val maxTestRunMillis: Long = 3 * MINUTE,

    // ----- 알람 (설계서 5장 ②, 지적 사항 #3) -----
    /** 한 번 울리는 시간 */
    val alarmRingDurationMillis: Long = 30 * SECOND,
    /** 울림 시작 간격 (1분마다 다시 울림) */
    val alarmRepeatIntervalMillis: Long = 1 * MINUTE,
    /** 최대 울림 횟수. 이후에는 알람을 멈추고 화면만 유지 */
    val alarmMaxRings: Int = 5,

    // ----- 깨우기 순서 (설계서 5장 ①) -----
    /** 휴식이 이보다 짧으면 깨우기 단계 시점을 비율로 줄인다 (예: 1분 휴식 → 5분 전이 30초 전) */
    val fullWakeSequenceMinRestMillis: Long = 10 * MINUTE,
    /** 앱 재실행 등으로 이미 지난 깨우기 단계는 이 시간 이상 지났으면 건너뛴다 (S-03: 시트 이동 재개 금지) */
    val skipPastWakeStepToleranceMillis: Long = 5 * SECOND,

    // ----- 2단계(차량 제어)에서 사용 -----
    /** S-03: 시트 한 번 이동 최대 시간 */
    val seatMoveStepMaxMillis: Long = 2 * SECOND,
    /** S-03: 시트 이동 전체 최대 시간 */
    val seatMoveTotalMaxMillis: Long = 15 * SECOND,
    /** S-07: 배터리 하한선 (%) — D-2 미정, 임시값 */
    val batteryLowPercent: Int = 20,
) {
    val minRestMillis: Long get() = minRestMinutes * MINUTE
    val maxRestMillis: Long get() = maxRestMinutes * MINUTE

    /** 프리셋 시간으로 써도 되는 값인지 (전체 한계 안, restMinutesStep 단위) */
    fun isAllowedRestMinutes(minutes: Int): Boolean =
        minutes in minRestMinutes..maxRestMinutes && minutes % restMinutesStep == 0

    /** 범위를 벗어난 값을 가장 가까운 허용 값으로 맞춘다. */
    fun clampRestMinutes(minutes: Int): Int {
        val rounded = (minutes / restMinutesStep) * restMinutesStep
        return rounded.coerceIn(minRestMinutes, maxRestMinutes)
    }

    companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
    }
}
