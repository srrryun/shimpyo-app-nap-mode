package com.example.hyundaitest.data.model

/** 휴식이 중간에 멈춘 이유. message 는 화면에 그대로 보여준다. */
enum class StopReason(val message: String) {
    USER_PRESSED_STOP("직접 휴식을 멈췄어요."),
    APP_INTERRUPTED("앱이 중간에 종료되어 휴식이 끊겼어요."),

    // 차량 감시(VehicleMonitor)가 감지한 이유
    DOOR_UNLOCKED("문 잠금이 풀려서 휴식을 멈췄어요."),
    DOOR_OPENED("문이 열려서 휴식을 멈췄어요."),
    BRAKE_PRESSED("브레이크 조작이 감지되어 휴식을 멈췄어요."),
    PARKING_RELEASED("주차 상태가 풀려서 휴식을 멈췄어요. P단과 파킹브레이크를 확인해 주세요."),
    /** 중단이 아니라 조기 기상(알람)에 쓰인다 */
    BATTERY_LOW("배터리가 부족해서 일찍 깨웠어요."),
    VEHICLE_DISCONNECTED("차량 연결이 끊겨서 휴식을 멈췄어요."),
}
