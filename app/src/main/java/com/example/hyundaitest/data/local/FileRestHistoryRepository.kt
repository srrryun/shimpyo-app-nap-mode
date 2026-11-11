package com.example.hyundaitest.data.local

import android.content.Context
import com.example.hyundaitest.data.RestHistoryRepository
import com.example.hyundaitest.data.model.RestRecord
import java.io.File

/**
 * 휴식 기록 저장소 (1차 구현). 고민 내용은 들어가지 않으므로 일반 폴더(filesDir)에 저장한다.
 * 기록이 너무 많아지지 않게 최근 MAX_RECORDS 개만 남긴다.
 */
class FileRestHistoryRepository(context: Context) : RestHistoryRepository {
    private val store = JsonListFile(
        file = File(context.applicationContext.filesDir, FILE_NAME),
        logTag = LOG_TAG,
    )

    override suspend fun addRecord(record: RestRecord) {
        store.updateItems { items ->
            items.add(JsonMappers.restRecordToJson(record))
            while (items.size > MAX_RECORDS) items.removeAt(0)
        }
    }

    override suspend fun loadRecords(): List<RestRecord> =
        store.readItems()
            .map(JsonMappers::restRecordFromJson)
            .sortedByDescending { it.endedAtMillis }

    override suspend fun loadLatestRecord(): RestRecord? = loadRecords().firstOrNull()

    private companion object {
        const val FILE_NAME = "rest_history.json"
        const val LOG_TAG = "ShimpyoHistory"
        const val MAX_RECORDS = 500
    }
}
