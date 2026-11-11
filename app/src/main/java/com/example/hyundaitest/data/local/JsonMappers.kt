package com.example.hyundaitest.data.local

import com.example.hyundaitest.data.model.BambooNote
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.RestRecord
import com.example.hyundaitest.data.model.StopReason
import org.json.JSONObject

/**
 * 모델 <-> JSON 변환을 한 곳에 모았다.
 * 저장 형식이 바뀌면 SCHEMA_VERSION 을 올리고 여기서 옛 형식도 읽을 수 있게 고친다.
 * Room 전환 때 "기존 파일 → DB 이전"에서도 이 함수들을 그대로 재사용한다.
 */
object JsonMappers {
    const val SCHEMA_VERSION = 1
    const val KEY_SCHEMA_VERSION = "schemaVersion"

    fun bambooNoteToJson(note: BambooNote): JSONObject = JSONObject()
        .put("id", note.id)
        .put("text", note.text)
        .put("createdAtMillis", note.createdAtMillis)
        .put("isKept", note.isKept)

    fun bambooNoteFromJson(json: JSONObject): BambooNote = BambooNote(
        id = json.getString("id"),
        text = json.getString("text"),
        createdAtMillis = json.getLong("createdAtMillis"),
        isKept = json.optBoolean("isKept", false),
    )

    fun restRecordToJson(record: RestRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("presetName", record.presetName)
        .put("plannedDurationMillis", record.plannedDurationMillis)
        .put("startedAtMillis", record.startedAtMillis)
        .put("endedAtMillis", record.endedAtMillis)
        .put("actualRestMillis", record.actualRestMillis)
        .put("endedNormally", record.endedNormally)
        .put("stopReason", record.stopReason?.name ?: JSONObject.NULL)
        .put("isTestRun", record.isTestRun)

    fun restRecordFromJson(json: JSONObject): RestRecord = RestRecord(
        id = json.getString("id"),
        presetName = json.getString("presetName"),
        plannedDurationMillis = json.getLong("plannedDurationMillis"),
        startedAtMillis = json.getLong("startedAtMillis"),
        endedAtMillis = json.getLong("endedAtMillis"),
        actualRestMillis = json.getLong("actualRestMillis"),
        endedNormally = json.getBoolean("endedNormally"),
        stopReason = stopReasonOrNull(json.optString("stopReason", "")),
        isTestRun = json.optBoolean("isTestRun", false),
    )

    fun napSessionToJson(session: NapSession): JSONObject = JSONObject()
        .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        .put("id", session.id)
        .put("presetName", session.presetName)
        .put("plannedDurationMillis", session.plannedDurationMillis)
        .put("startedAtMillis", session.startedAtMillis)
        .put("endsAtMillis", session.endsAtMillis)
        .put("bambooNoteId", session.bambooNoteId ?: JSONObject.NULL)
        .put("isTestRun", session.isTestRun)

    fun napSessionFromJson(json: JSONObject): NapSession = NapSession(
        id = json.getString("id"),
        presetName = json.getString("presetName"),
        plannedDurationMillis = json.getLong("plannedDurationMillis"),
        startedAtMillis = json.getLong("startedAtMillis"),
        endsAtMillis = json.getLong("endsAtMillis"),
        bambooNoteId = if (json.isNull("bambooNoteId")) null else json.getString("bambooNoteId"),
        isTestRun = json.optBoolean("isTestRun", false),
    )

    private fun stopReasonOrNull(name: String): StopReason? =
        StopReason.entries.firstOrNull { it.name == name }
}
