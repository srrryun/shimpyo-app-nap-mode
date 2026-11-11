package com.example.hyundaitest.ui

import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.vehicle.ComfortItem
import com.example.hyundaitest.vehicle.ControlStatus

/*
 * 여러 화면이 같이 쓰는 문구와 상태 색. 문구를 바꿀 때는 여기만 고친다.
 */

fun parkingLabel(state: ParkingState): String = when (state) {
    ParkingState.PARKED -> "주차 확인됨"
    ParkingState.NOT_PARKED -> "주차 상태 아님"
    ParkingState.UNKNOWN -> "주차 자동 확인 불가"
}

fun parkingColor(state: ParkingState): Int = when (state) {
    ParkingState.PARKED -> ShimpyoColors.OK
    ParkingState.NOT_PARKED -> ShimpyoColors.DANGER
    ParkingState.UNKNOWN -> ShimpyoColors.WARN
}

/** 홈 제목 아래 한 줄 */
fun parkingGuide(state: ParkingState): String = when (state) {
    ParkingState.PARKED -> "차가 안전하게 주차돼 있어요. 고르면 바로 쉴 수 있어요."
    ParkingState.NOT_PARKED -> "P단에 두고 파킹브레이크를 걸면 쉴 수 있어요."
    ParkingState.UNKNOWN -> "시작할 때 차가 서 있는지 한 번만 물어볼게요."
}

/** 쉬는 동안 차가 해 줄 일 — 묶음 이름과 설명 (홈·시작 확인·설정에서 같이 쓴다) */
const val COMFORT_SECTION_TITLE = "쉬는 동안 차가 해 줄 일"
const val COMFORT_SECTION_GUIDE = "켜 둔 것만 차가 조절하고, 깨어날 때 원래대로 돌려놔요."

/** 차가 해 줄 일 이름 (휴식 시작 때 하는 일 기준) */
fun comfortLabel(item: ComfortItem): String = when (item) {
    ComfortItem.AMBIENT_LIGHT -> "조명 은은하게"
    ComfortItem.CLIMATE -> "실내 온도 맞추기"
    ComfortItem.SEAT_BACKREST -> "등받이 눕히기"
}

/** 시간대에 맞는 홈 인사 */
fun greetingFor(hourOfDay: Int): String = when (hourOfDay) {
    in 5..10 -> "출발 전에 잠깐 쉬어 갈까요?"
    in 11..14 -> "점심 먹고 잠깐 쉬어 갈까요?"
    in 15..18 -> "오후에 잠깐 쉬어 갈까요?"
    else -> "오늘은 얼마나 쉴까요?"
}

fun presetDescription(presetId: String): String = when (presetId) {
    "short" -> "눈만 잠깐 붙이기"
    "light" -> "짧고 개운한 낮잠"
    "full" -> "조금 더 깊이 쉬기"
    else -> ""
}

/** 제어 결과 4가지의 색 */
fun controlStatusColor(status: ControlStatus, night: Boolean = false): Int = when (status) {
    ControlStatus.CONFIRMED -> if (night) ShimpyoColors.NIGHT_OK else ShimpyoColors.OK
    ControlStatus.REQUESTED, ControlStatus.NOT_APPLIED -> if (night) ShimpyoColors.NIGHT_TEXT_SECONDARY else ShimpyoColors.WARN
    ControlStatus.UNSUPPORTED -> if (night) ShimpyoColors.NIGHT_TEXT_SECONDARY else ShimpyoColors.TEXT_TERTIARY
}
