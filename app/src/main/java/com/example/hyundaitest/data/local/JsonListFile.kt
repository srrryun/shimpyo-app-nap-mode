package com.example.hyundaitest.data.local

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON 목록 하나를 파일 하나에 저장하는 작은 도구.
 * 파일 형식: { "schemaVersion": 1, "items": [ {...}, {...} ] }
 *
 * - AtomicFile 로 저장해서, 저장 도중 앱이 꺼져도 파일이 깨지지 않는다.
 * - Mutex 로 동시에 읽고 쓰는 것을 막는다.
 * - 파일이 깨졌거나 없으면 빈 목록으로 시작한다 (앱이 멈추지 않게).
 * - 내용(고민 텍스트 등)은 로그에 남기지 않는다. 오류 종류만 남긴다.
 */
class JsonListFile(file: File, private val logTag: String) {
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()

    suspend fun readItems(): List<JSONObject> = mutex.withLock { withContext(Dispatchers.IO) { readUnlocked() } }

    /** 목록 전체를 읽고 → 바꾸고 → 저장하는 작업을 한 번에(다른 작업이 끼어들지 않게) 한다. */
    suspend fun updateItems(change: (MutableList<JSONObject>) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val items = readUnlocked().toMutableList()
            change(items)
            writeUnlocked(items)
        }
    }

    private fun readUnlocked(): List<JSONObject> {
        if (!atomicFile.baseFile.exists()) return emptyList()
        return try {
            val text = atomicFile.readFully().toString(Charsets.UTF_8)
            val root = JSONObject(text)
            val array = root.optJSONArray("items") ?: JSONArray()
            List(array.length()) { index -> array.getJSONObject(index) }
        } catch (e: Exception) {
            Log.w(logTag, "저장 파일을 읽지 못해 빈 목록으로 시작해요: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun writeUnlocked(items: List<JSONObject>) {
        val root = JSONObject()
            .put(JsonMappers.KEY_SCHEMA_VERSION, JsonMappers.SCHEMA_VERSION)
            .put("items", JSONArray(items))
        val stream = atomicFile.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            throw e
        }
    }
}
