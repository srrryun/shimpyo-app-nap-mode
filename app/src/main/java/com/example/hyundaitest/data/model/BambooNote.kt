package com.example.hyundaitest.data.model

/**
 * 대나무숲에 맡겨둔 이야기 한 건.
 * 주의: text 는 절대 로그에 남기지 않는다 (설계서 P-04). toString 도 내용을 숨긴다.
 */
data class BambooNote(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    /** true = 사용자가 "보관하기"를 고름. false = 아직 고르지 않음(휴식 완료 때 삭제 확인). */
    val isKept: Boolean,
) {
    override fun toString(): String =
        "BambooNote(id=$id, createdAtMillis=$createdAtMillis, isKept=$isKept, text=<숨김 ${text.length}자>)"
}
