package com.example.hyundaitest.safety

/**
 * 차가 주차 상태인지 자동으로 읽은 결과 (설계서 8장 S-01).
 *
 *  - PARKED     : 바로 시작 가능 (체크 없음)
 *  - NOT_PARKED : 시작을 막고 경고
 *  - UNKNOWN    : 자동으로 읽지 못함 → 사용자가 "정차 중이에요"를 한 번 눌러야 시작
 */
enum class ParkingState(val label: String) {
    PARKED("주차 상태 확인됨"),
    NOT_PARKED("주차 상태가 아니에요"),
    UNKNOWN("자동 확인 불가"),
}

/**
 * 정보를 읽어 온 한 곳의 결과.
 * @param sourceName 화면·로그에 보여줄 이름 (예: "파킹브레이크 · 안드로이드 표준")
 * @param value true = 주차 쪽(브레이크 걸림 / P단), false = 주차 아님, null = 읽지 못함
 * @param note 읽은 값이나 실패 이유 (개발용)
 */
data class ParkingSourceResult(
    val sourceName: String,
    val kind: Kind,
    val value: Boolean?,
    val note: String,
) {
    enum class Kind { PARKING_BRAKE, GEAR_IN_PARK }
}

/** 여러 곳에서 읽은 결과를 합친 최종 판단 */
data class ParkingReading(
    val state: ParkingState,
    val isParkingBrakeOn: Boolean?,
    val isGearInPark: Boolean?,
    val sources: List<ParkingSourceResult>,
) {
    companion object {
        val UNKNOWN = ParkingReading(ParkingState.UNKNOWN, null, null, emptyList())

        /**
         * 판정 규칙 — 바꾸고 싶으면 여기만 고친다.
         *
         * 같은 항목을 여러 곳에서 읽었는데 값이 다르면 "아님(false)"을 믿는다 (안전한 쪽).
         *  1. 기어가 P가 아니라고 읽힘(D·R·N)           → NOT_PARKED
         *     (전자식 파킹브레이크는 D단에서 가속 페달을 밟으면 자동으로 풀릴 수 있어서)
         *  2. 파킹브레이크 걸림 또는 P단                  → PARKED
         *  3. 파킹브레이크가 풀려 있다고 읽힘              → NOT_PARKED
         *  4. 아무것도 못 읽음                           → UNKNOWN
         */
        fun fromSources(sources: List<ParkingSourceResult>): ParkingReading {
            val brakeOn = combine(sources.filter { it.kind == ParkingSourceResult.Kind.PARKING_BRAKE })
            val gearInPark = combine(sources.filter { it.kind == ParkingSourceResult.Kind.GEAR_IN_PARK })
            val state = when {
                gearInPark == false -> ParkingState.NOT_PARKED
                brakeOn == true || gearInPark == true -> ParkingState.PARKED
                brakeOn == false -> ParkingState.NOT_PARKED
                else -> ParkingState.UNKNOWN
            }
            return ParkingReading(state, brakeOn, gearInPark, sources)
        }

        private fun combine(results: List<ParkingSourceResult>): Boolean? {
            val values = results.mapNotNull { it.value }
            return when {
                values.isEmpty() -> null
                values.any { !it } -> false
                else -> true
            }
        }
    }
}
