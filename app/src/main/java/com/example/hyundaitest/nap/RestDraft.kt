package com.example.hyundaitest.nap

import com.example.hyundaitest.vehicle.ComfortItem

/**
 * 휴식을 시작하기 전, 화면 사이(홈 → 대나무숲 → 시작 확인)에서 고른 값을 잠깐 들고 있는 곳.
 *
 * - 메모리에만 있고 파일에 저장하지 않는다. 앱이 꺼지면 사라진다.
 * - 대나무숲 글은 휴식을 시작하는 순간 암호화 저장소로 옮겨지고, 여기서는 지운다.
 * - 프리셋 선택·시간은 PresetRepository 에 따로 저장된다(앱을 다시 켜도 유지).
 */
class RestDraft {
    /** 개발용 1분 테스트 휴식인지 */
    var isTestRun: Boolean = false

    /** 사용자가 고른 차량 환경 항목. null = 아직 안 건드림 → 지원하는 항목 전부 켬 */
    var approvedItems: Set<ComfortItem>? = null

    /** 대나무숲에 맡길 글 (비어 있으면 맡기지 않음) */
    var bambooText: String = ""

    val hasBambooText: Boolean get() = bambooText.isNotBlank()

    /** 지원하는 항목 중 실제로 켤 항목 */
    fun approvedOrAll(supported: Set<ComfortItem>): Set<ComfortItem> =
        (approvedItems ?: supported) intersect supported

    /** 휴식을 시작한 뒤 비운다 (항목 선택은 다음 휴식에도 그대로 쓰도록 남긴다) */
    fun clearAfterStart() {
        isTestRun = false
        bambooText = ""
    }
}
