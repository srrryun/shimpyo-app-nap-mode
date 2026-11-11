package com.example.hyundaitest.vehicle

import com.example.hyundaitest.data.model.NapSession

/**
 * 휴식 환경으로 제어할 수 있는 항목 (설계서 7장 기능 5).
 * 블라인드·실내등은 일정상 이번에 넣지 않았다(설계서 12.7). 넣을 때는 여기에 항목을 추가하고
 * PleosRestEnvironment 에 구현을 추가한다.
 */
enum class ComfortItem(val label: String) {
    AMBIENT_LIGHT("무드등"),
    CLIMATE("공조"),
    SEAT_BACKREST("운전석 등받이"),
}

/**
 * 휴식 환경(시트·공조·조명)을 다루는 약속 — 설계서 7장 기능 5.
 *
 * NapController 는 차량 SDK 를 직접 부르지 않고 이 인터페이스만 사용한다.
 * - 차량 연결 실패·차량 없음: NoVehicleRestEnvironment (아무것도 제어하지 않음)
 * - Pleos 차량:              PleosRestEnvironment
 * AppContainer 에서 어느 것을 쓸지 고른다.
 */
interface RestEnvironment {
    /**
     * 이번 휴식에서 차량 제어를 실제로 하는지 (승인한 항목이 하나라도 있는지).
     * false 면 화면에 "제어했다"고 표시하지 않는다.
     */
    val isVehicleControlEnabled: Boolean

    /** 이 차에서 제어할 수 있는 항목 (홈 화면 승인 목록용). 기본: 없음 */
    suspend fun findSupportedItems(): Set<ComfortItem> = emptySet()

    /**
     * 휴식 시작 직후: 현재 상태 저장(스냅샷) → 휴식 환경 세팅.
     * @param approvedItems 사용자가 시작 전에 승인한 항목. 이 밖의 항목은 절대 건드리지 않는다 (S-02).
     */
    suspend fun prepareForRest(session: NapSession, approvedItems: Set<ComfortItem>): List<ControlResult>

    /** 깨우기 단계: 조명을 서서히 밝힘 */
    suspend fun wakeStepLightUp()

    /** 깨우기 단계: 외기 들이기·온도 조금 낮추기 */
    suspend fun wakeStepFreshAir()

    /** 깨우기 단계: 등받이를 짧게 나눠 세우기 */
    suspend fun wakeStepRaiseSeat()

    /** 휴식 시작 전 상태로 되돌리기 */
    suspend fun restoreOriginalState(): List<ControlResult>

    /** 즉시 중단: 움직이는 것(시트)을 모두 멈춘다. 오래 걸리면 안 된다. (S-05) */
    fun stopAllMovementNow()
}

/** 차량 제어 결과 표시 4가지 (설계서 7장 규칙) */
enum class ControlStatus(val label: String) {
    REQUESTED("요청함"),
    CONFIRMED("반영 확인"),
    NOT_APPLIED("반영 안 됨"),
    UNSUPPORTED("미지원"),
}

/** 차량 제어 한 항목의 결과 */
data class ControlResult(
    val itemName: String,
    val status: ControlStatus,
    val detail: String? = null,
)

/** 차량을 전혀 제어하지 않는 구현 (차량 연결 실패·테스트) */
class NoVehicleRestEnvironment : RestEnvironment {
    override val isVehicleControlEnabled: Boolean = false
    override suspend fun prepareForRest(session: NapSession, approvedItems: Set<ComfortItem>): List<ControlResult> =
        emptyList()
    override suspend fun wakeStepLightUp() = Unit
    override suspend fun wakeStepFreshAir() = Unit
    override suspend fun wakeStepRaiseSeat() = Unit
    override suspend fun restoreOriginalState(): List<ControlResult> = emptyList()
    override fun stopAllMovementNow() = Unit
}
