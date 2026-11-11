package com.example.hyundaitest.data

import com.example.hyundaitest.data.model.BambooNote
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestPreset
import com.example.hyundaitest.data.model.RestRecord

/*
 * 저장소 "약속(인터페이스)" 모음 — 설계서 11.1 확장 설계.
 *
 * 화면과 NapController 는 이 인터페이스만 사용한다.
 * 1차 구현: data/local 폴더의 Prefs…/File… 클래스
 * 2차 구현(예정): Room…Repository 를 새로 만들고 AppContainer 에서 갈아 끼운다.
 * 모든 함수는 suspend 다 (Room DAO 와 같은 방식이라 전환 시 호출하는 쪽 코드가 바뀌지 않는다).
 */

/** 휴식 프리셋 저장소 */
interface PresetRepository {
    /** 기본 프리셋 + 사용자가 바꾼 시간을 합쳐서 돌려준다. */
    suspend fun loadPresets(): List<RestPreset>

    /** 프리셋 시간을 바꿔 저장한다. 값 검증은 호출하는 쪽(SafetyRules)에서 한다. */
    suspend fun savePresetMinutes(presetId: String, minutes: Int)

    suspend fun loadSelectedPresetId(): String?

    suspend fun saveSelectedPresetId(presetId: String)
}

/** 대나무숲 저장소. 기기 안에만, 백업 제외 위치에 저장한다. */
interface BambooRepository {
    suspend fun saveNewNote(text: String, createdAtMillis: Long): BambooNote

    /** 없으면(이미 지웠으면) null */
    suspend fun findNote(noteId: String): BambooNote?

    /** 보관한 이야기 목록 (최신 순) */
    suspend fun loadKeptNotes(): List<BambooNote>

    suspend fun markNoteAsKept(noteId: String)

    suspend fun deleteNote(noteId: String)

    /** 보안 상태 화면용: 암호화되지 않은 채 저장된 이야기 수 (정상이면 0) */
    suspend fun countUnencryptedNotes(): Int
}

/** 휴식 기록 저장소 */
interface RestHistoryRepository {
    suspend fun addRecord(record: RestRecord)

    /** 최신 순 */
    suspend fun loadRecords(): List<RestRecord>

    suspend fun loadLatestRecord(): RestRecord?
}

/** 진행 중인 휴식 저장소 (앱 재실행 시 복구용) */
interface NapSessionRepository {
    suspend fun saveActiveSession(session: NapSession)

    suspend fun loadActiveSession(): NapSession?

    suspend fun clearActiveSession()
}
