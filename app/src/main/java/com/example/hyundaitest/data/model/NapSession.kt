package com.example.hyundaitest.data.model

/**
 * 지금 진행 중인 휴식. 앱이 꺼졌다 켜져도 이어갈 수 있도록 저장소에 저장한다.
 */
data class NapSession(
    val id: String,
    val presetName: String,
    val plannedDurationMillis: Long,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    /** 대나무숲에 맡긴 이야기가 있으면 그 id, 없으면 null */
    val bambooNoteId: String?,
    /** 개발용 1분 테스트 휴식 여부 */
    val isTestRun: Boolean,
)
