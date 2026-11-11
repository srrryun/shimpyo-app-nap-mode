package com.example.hyundaitest.nap

import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.RestEnvironment

/**
 * "몸으로 깨우기" 단계 하나 (설계서 5장 ①, 11.1 확장 설계).
 * 순서를 코드에 고정하지 않고 목록으로 둔다. 연출을 추가·변경할 때는 defaultSteps() 만 고친다.
 *
 * @param millisBeforeEnd 휴식 종료 몇 ms 전에 실행할지
 * @param guidanceWithVehicle 차량 제어가 켜져 있을 때 화면 안내
 * @param guidanceWithoutVehicle 차량 제어가 꺼져 있을 때 화면 안내 (실제로 하지 않는 일을 했다고 말하지 않기 위해)
 * @param action 실제 동작. 차량 제어는 RestEnvironment 를 통해서만 한다.
 */
data class WakeStep(
    val id: String,
    val millisBeforeEnd: Long,
    val guidanceWithVehicle: String,
    val guidanceWithoutVehicle: String,
    val action: suspend (RestEnvironment) -> Unit,
) {
    fun guidanceFor(environment: RestEnvironment): String =
        if (environment.isVehicleControlEnabled) guidanceWithVehicle else guidanceWithoutVehicle

    companion object {
        fun defaultSteps(): List<WakeStep> = listOf(
            WakeStep(
                id = "light_up",
                millisBeforeEnd = 5 * SafetyRules.MINUTE,
                guidanceWithVehicle = "조명을 서서히 밝히고 있어요.",
                guidanceWithoutVehicle = "곧 깨워 드릴게요. 천천히 숨을 쉬어 보세요.",
                action = { environment -> environment.wakeStepLightUp() },
            ),
            WakeStep(
                id = "fresh_air",
                millisBeforeEnd = 3 * SafetyRules.MINUTE,
                guidanceWithVehicle = "바깥 공기를 들이고 있어요.",
                guidanceWithoutVehicle = "곧 깨워 드릴게요. 몸을 조금씩 움직여 보세요.",
                action = { environment -> environment.wakeStepFreshAir() },
            ),
            WakeStep(
                id = "raise_seat",
                millisBeforeEnd = 2 * SafetyRules.MINUTE,
                guidanceWithVehicle = "등받이를 천천히 세우고 있어요.",
                guidanceWithoutVehicle = "곧 알람이 울려요.",
                action = { environment -> environment.wakeStepRaiseSeat() },
            ),
        )

        /**
         * 이번 휴식 길이에 맞춘 실행 시점 목록 (먼저 실행할 단계가 앞).
         * 휴식이 fullSequenceMinRestMillis 보다 짧으면 시점을 같은 비율로 줄인다.
         * 예) 1분 테스트 휴식: 5분 전 → 30초 전, 3분 전 → 18초 전, 2분 전 → 12초 전
         */
        fun scheduleFor(
            steps: List<WakeStep>,
            restDurationMillis: Long,
            fullSequenceMinRestMillis: Long,
        ): List<ScheduledWakeStep> {
            val scale = if (restDurationMillis >= fullSequenceMinRestMillis) {
                1.0
            } else {
                restDurationMillis.toDouble() / fullSequenceMinRestMillis
            }
            return steps
                .map { step -> ScheduledWakeStep(step, (step.millisBeforeEnd * scale).toLong()) }
                .sortedByDescending { it.millisBeforeEnd }
        }
    }
}

/** 이번 휴식에 맞춰 시점을 정한 깨우기 단계 */
data class ScheduledWakeStep(val step: WakeStep, val millisBeforeEnd: Long)
