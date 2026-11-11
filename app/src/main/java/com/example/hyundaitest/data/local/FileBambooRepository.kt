package com.example.hyundaitest.data.local

import android.content.Context
import android.util.Log
import com.example.hyundaitest.data.BambooRepository
import com.example.hyundaitest.data.model.BambooNote
import com.example.hyundaitest.security.TextCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 대나무숲 저장소 (1차 구현).
 *
 * 보안 (설계서 6장, 8장 P-02·P-03·P-04, 2단계 보안 A)
 *  - 저장 위치: context.noBackupFilesDir — 안드로이드 자동 백업 대상에서 빠지는 폴더
 *  - 고민 내용(text)은 TextCipher(키스토어 AES-256-GCM)로 암호화해서 저장한다.
 *    id·작성 시각·보관 여부만 평문이다.
 *  - 예전(암호화 전)에 저장한 글은 처음 접근할 때 한 번 암호화해서 다시 저장한다.
 *  - 내용은 서버로 보내지 않고, 로그에도 남기지 않는다(오류 종류만).
 */
class FileBambooRepository(
    context: Context,
    private val cipher: TextCipher,
) : BambooRepository {
    private val store = JsonListFile(
        file = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
        logTag = LOG_TAG,
    )
    private val migrationLock = Mutex()
    private var hasCheckedLegacyNotes = false

    override suspend fun saveNewNote(text: String, createdAtMillis: Long): BambooNote {
        encryptLegacyNotesOnce()
        val note = BambooNote(
            id = UUID.randomUUID().toString(),
            text = text,
            createdAtMillis = createdAtMillis,
            isKept = false,
        )
        val json = toEncryptedJson(note)
        store.updateItems { items -> items.add(json) }
        return note
    }

    override suspend fun findNote(noteId: String): BambooNote? {
        encryptLegacyNotesOnce()
        return store.readItems().firstOrNull { it.optString("id") == noteId }?.let { fromStoredJson(it) }
    }

    override suspend fun loadKeptNotes(): List<BambooNote> {
        encryptLegacyNotesOnce()
        return store.readItems()
            .filter { it.optBoolean("isKept", false) }
            .map { fromStoredJson(it) }
            .sortedByDescending { it.createdAtMillis }
    }

    override suspend fun markNoteAsKept(noteId: String) {
        store.updateItems { items ->
            val index = items.indexOfFirst { it.optString("id") == noteId }
            if (index >= 0) items[index] = items[index].put("isKept", true)
        }
    }

    override suspend fun deleteNote(noteId: String) {
        store.updateItems { items -> items.removeAll { it.optString("id") == noteId } }
    }

    /** 보안 상태 화면용: 암호화되지 않은 채 저장된 글의 개수 (정상이면 0) */
    override suspend fun countUnencryptedNotes(): Int {
        encryptLegacyNotesOnce()
        return store.readItems().count { !it.optBoolean(KEY_ENCRYPTED, false) }
    }

    // ------------------------------------------------------------------

    private suspend fun toEncryptedJson(note: BambooNote): JSONObject = withContext(Dispatchers.IO) {
        JsonMappers.bambooNoteToJson(note.copy(text = cipher.encrypt(note.text)))
            .put(KEY_ENCRYPTED, true)
    }

    private suspend fun fromStoredJson(json: JSONObject): BambooNote = withContext(Dispatchers.IO) {
        val stored = JsonMappers.bambooNoteFromJson(json)
        if (!json.optBoolean(KEY_ENCRYPTED, false)) return@withContext stored
        try {
            stored.copy(text = cipher.decrypt(stored.text))
        } catch (e: Exception) {
            // 키가 사라졌거나(앱 데이터 초기화) 파일이 변조된 경우. 내용은 로그에 남기지 않는다.
            Log.w(LOG_TAG, "이야기를 복호화하지 못했어요: ${e.javaClass.simpleName}")
            stored.copy(text = UNREADABLE_TEXT)
        }
    }

    private suspend fun encryptLegacyNotesOnce() = migrationLock.withLock {
        if (hasCheckedLegacyNotes) return@withLock
        hasCheckedLegacyNotes = true
        val hasLegacy = store.readItems().any { !it.optBoolean(KEY_ENCRYPTED, false) }
        if (!hasLegacy) return@withLock
        try {
            store.updateItems { items ->
                for (index in items.indices) {
                    val item = items[index]
                    if (item.optBoolean(KEY_ENCRYPTED, false)) continue
                    item.put("text", cipher.encrypt(item.optString("text")))
                    item.put(KEY_ENCRYPTED, true)
                }
            }
            Log.i(LOG_TAG, "암호화 전에 저장된 이야기를 암호화했어요")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "예전 이야기 암호화 실패: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val FILE_NAME = "bamboo_notes.json"
        const val LOG_TAG = "ShimpyoBamboo"
        const val KEY_ENCRYPTED = "textEncrypted"
        const val UNREADABLE_TEXT = "(이 기기에서 더 이상 읽을 수 없는 이야기예요)"
    }
}
