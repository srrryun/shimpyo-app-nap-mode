package com.example.hyundaitest.data.local

import android.content.Context
import com.example.hyundaitest.data.PresetRepository
import com.example.hyundaitest.data.model.RestPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 프리셋 저장소 (1차 구현). 바꾼 시간과 마지막 선택만 SharedPreferences 에 저장한다. */
class PrefsPresetRepository(context: Context) : PresetRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun loadPresets(): List<RestPreset> = withContext(Dispatchers.IO) {
        RestPreset.DEFAULTS.map { preset ->
            // 예전에 저장한 값이 새 범위를 벗어나면(예: 45분) 범위 끝으로 맞춘다
            preset.withMinutes(prefs.getInt(minutesKey(preset.id), preset.minutes))
        }
    }

    override suspend fun savePresetMinutes(presetId: String, minutes: Int) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(minutesKey(presetId), minutes).apply()
    }

    override suspend fun loadSelectedPresetId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SELECTED_PRESET, null)
    }

    override suspend fun saveSelectedPresetId(presetId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SELECTED_PRESET, presetId).apply()
    }

    private fun minutesKey(presetId: String) = "preset_minutes_$presetId"

    private companion object {
        const val PREFS_NAME = "shimpyo_presets"
        const val KEY_SELECTED_PRESET = "selected_preset_id"
    }
}
