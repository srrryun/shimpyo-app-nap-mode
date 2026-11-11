package com.example.hyundaitest.data.model

/**
 * 휴식 프리셋 (짧은 휴식 · 가벼운 낮잠 · 충분한 휴식).
 * 각 프리셋은 조절할 수 있는 범위(minMinutes~maxMinutes)를 가진다.
 * 범위가 겹치지 않게 두어서, 짧은 휴식이 가벼운 낮잠보다 길어지는 일이 없다. (설계서 4장)
 * 2단계에서 차량 환경 항목(시트·공조·조명·블라인드 켜짐/꺼짐)을 이 클래스에 추가한다.
 */
data class RestPreset(
    val id: String,
    val name: String,
    val minutes: Int,
    val minMinutes: Int,
    val maxMinutes: Int,
) {
    /** 범위를 벗어난 값은 범위 끝으로 맞춘 새 프리셋 */
    fun withMinutes(newMinutes: Int): RestPreset = copy(minutes = newMinutes.coerceIn(minMinutes, maxMinutes))

    val rangeText: String get() = "$minMinutes~${maxMinutes}분"

    companion object {
        const val ID_SHORT = "short"
        const val ID_LIGHT = "light"
        const val ID_FULL = "full"

        /**
         * 설계서 4장 기본 프리셋과 조절 범위.
         * 전체 상한 30분: 30분을 넘기면 깊은 잠에 들어 깬 직후 멍한 상태(수면 관성)가 심해진다.
         * 범위를 바꿀 때는 SafetyRules.minRestMinutes / maxRestMinutes 와 맞춘다.
         */
        val DEFAULTS: List<RestPreset> = listOf(
            RestPreset(ID_SHORT, "짧은 휴식", minutes = 10, minMinutes = 5, maxMinutes = 15),
            RestPreset(ID_LIGHT, "가벼운 낮잠", minutes = 20, minMinutes = 15, maxMinutes = 25),
            RestPreset(ID_FULL, "충분한 휴식", minutes = 30, minMinutes = 25, maxMinutes = 30),
        )
    }
}
