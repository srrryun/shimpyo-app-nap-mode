package com.example.hyundaitest.data.local

import android.content.Context
import android.util.Log
import com.example.hyundaitest.data.NapSessionRepository
import com.example.hyundaitest.data.model.NapSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 진행 중인 휴식 저장소 (1차 구현). 휴식이 끝나면 지운다. */
class PrefsNapSessionRepository(context: Context) : NapSessionRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun saveActiveSession(session: NapSession) = withContext(Dispatchers.IO) {
        // commit(): 휴식 시작 직후 앱이 꺼져도 남아 있도록 바로 기록한다.
        prefs.edit().putString(KEY_ACTIVE_SESSION, JsonMappers.napSessionToJson(session).toString()).commit()
        Unit
    }

    override suspend fun loadActiveSession(): NapSession? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY_ACTIVE_SESSION, null) ?: return@withContext null
        try {
            JsonMappers.napSessionFromJson(JSONObject(text))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "진행 중 휴식 정보를 읽지 못했어요: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun clearActiveSession() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_ACTIVE_SESSION).commit()
        Unit
    }

    private companion object {
        const val PREFS_NAME = "shimpyo_session"
        const val KEY_ACTIVE_SESSION = "active_session"
        const val LOG_TAG = "ShimpyoSession"
    }
}
