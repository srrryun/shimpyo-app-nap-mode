package com.example.hyundaitest

import android.app.Application
import android.content.Context
import com.example.hyundaitest.data.local.applyToApp
import kotlinx.coroutines.launch

/** 앱 시작 시 AppContainer 를 만들고, 지난번에 끝나지 않은 휴식이 있으면 정리한 뒤 처음 화면부터 시작한다. */
class ShimpyoApplication : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * 앱이 뒤로 갔다가(다른 앱·홈 화면) 다시 앞으로 나왔는지. 홈 화면이 읽고 로딩 화면을 다시 보여준다.
     * 읽으면 지운다(consumeReturnedFromBackground).
     */
    @Volatile
    private var returnedFromBackground = false

    internal fun markReturnedFromBackground() {
        returnedFromBackground = true
    }

    fun consumeReturnedFromBackground(): Boolean {
        val value = returnedFromBackground
        returnedFromBackground = false
        return value
    }

    override fun onCreate() {
        super.onCreate()
        watchForegroundBackground()
        container = AppContainer(this)
        container.appSettings.themeMode.applyToApp()
        container.appScope.launch { container.napController.closeInterruptedSessionOnLaunch() }
    }
}

/** 화면에서 container 를 짧게 꺼내 쓰기 위한 도우미 */
val Context.appContainer: AppContainer
    get() = (applicationContext as ShimpyoApplication).container

/**
 * 앱 전체가 화면에서 사라졌다가(보이는 화면 0개) 다시 나타나는 순간을 잡는다.
 *  - 다시 나타날 때 설정·시작 확인 같은 중간 화면이 맨 위면, 처음 화면(홈)으로 돌린다 (11/11 사용자 결정).
 *  - 쉬는 중 휴식 화면이면 그대로 둔다 (알람이 울려야 하므로).
 *  - 홈으로 돌아오면 로딩 화면을 다시 보여준다.
 */
private fun ShimpyoApplication.watchForegroundBackground() {
    registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
        private var startedCount = 0
        private var wasInBackground = false

        override fun onActivityStarted(activity: android.app.Activity) {
            val comingBack = startedCount == 0 && wasInBackground
            startedCount++
            if (!comingBack) return
            wasInBackground = false
            when (activity) {
                is com.example.hyundaitest.ui.NapFlowActivity -> Unit // 쉬는 중: 그대로
                is com.example.hyundaitest.ui.HomeActivity -> markReturnedFromBackground()
                else -> {
                    markReturnedFromBackground()
                    activity.startActivity(
                        android.content.Intent(activity, com.example.hyundaitest.ui.HomeActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            if (startedCount == 0 && !activity.isChangingConfigurations) wasInBackground = true
        }

        // 화면을 그리기 전에 어두운 모드인지 맞춘다 (Activity.onCreate 의 super 안에서 불린다)
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = syncDarkMode(activity)
        override fun onActivityResumed(activity: android.app.Activity) = syncDarkMode(activity)

        private fun syncDarkMode(activity: android.app.Activity) {
            val nightBits = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            com.example.hyundaitest.ui.ShimpyoTheme.isDark = nightBits == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    })
}
