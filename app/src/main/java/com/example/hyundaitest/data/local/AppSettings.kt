package com.example.hyundaitest.data.local

import android.content.Context

/**
 * 앱 설정 (설정 화면). 기기 안 SharedPreferences 에만 저장한다.
 * 항목을 늘릴 때는 여기에 속성을 하나 더하고 설정 화면에 줄을 하나 더한다.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 쉬는 동안 차가 조명·온도·등받이를 조절해도 되는지 (설계서 7장 "휴식 환경 제어" 스위치).
     * 끄면 타이머·알람·대나무숲만으로 쉰다.
     */
    var isComfortControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMFORT_CONTROL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_COMFORT_CONTROL, value).apply()
        }

    /** 화면 모드 (다크 모드). 기본은 기기 설정을 따라간다 — 차가 밤에 어두운 모드로 바뀌면 같이 바뀐다. */
    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME_MODE, null) } ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    /** 슬립 모드: 쉬는 동안 화면을 만지지 않으면 화면을 꺼진 것처럼 어둡게 한다. 알람이 울리면 저절로 켜진다. */
    var isSleepModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SLEEP_MODE, value).apply()
        }

    /** 대나무숲 글씨 크기 단계 (MemoTextSize). 눈이 불편한 사람을 위해 쓰는 화면·보는 화면 모두 이 크기로 보여준다. */
    var memoTextSize: MemoTextSize
        get() = MemoTextSize.entries.firstOrNull { it.name == prefs.getString(KEY_MEMO_TEXT_SIZE, null) } ?: MemoTextSize.NORMAL
        set(value) {
            prefs.edit().putString(KEY_MEMO_TEXT_SIZE, value.name).apply()
        }

    private companion object {
        const val KEY_MEMO_TEXT_SIZE = "memo_text_size"
        const val PREFS_NAME = "shimpyo_settings"
        const val KEY_COMFORT_CONTROL = "comfort_control_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SLEEP_MODE = "sleep_mode_enabled"
    }
}

/** 대나무숲 글씨 크기 단계 */
enum class MemoTextSize(val label: String, val sp: Float) {
    NORMAL("보통", 26f),
    LARGE("크게", 34f),
    LARGER("더 크게", 42f),
    LARGEST("아주 크게", 52f),
    ;

    fun smaller(): MemoTextSize = entries.getOrElse(ordinal - 1) { this }
    fun larger(): MemoTextSize = entries.getOrElse(ordinal + 1) { this }
    val isSmallest get() = ordinal == 0
    val isLargest get() = ordinal == entries.lastIndex
}

/** 화면 모드 */
enum class ThemeMode(val label: String) {
    SYSTEM("기기 따라가기"),
    LIGHT("밝게"),
    DARK("어둡게"),
}

/** 고른 화면 모드를 앱 전체에 적용한다 (열려 있는 화면은 AppCompat 이 다시 그린다) */
fun ThemeMode.applyToApp() {
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        when (this) {
            ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        },
    )
}
