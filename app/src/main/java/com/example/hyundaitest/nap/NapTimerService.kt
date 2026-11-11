package com.example.hyundaitest.nap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.hyundaitest.ui.NapFlowActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴식 중 앱이 시스템에 의해 꺼지지 않도록 붙잡아 두는 포그라운드 서비스.
 *
 * 타이머 계산은 하지 않는다 (NapController 가 유일한 기준).
 * 이 서비스는 "앱이 살아 있게" + "휴식 중 알림 표시"만 맡는다.
 * 서비스 시작이 막혀도(백그라운드 제한 등) 휴식 자체는 계속된다.
 */
class NapTimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val endsAtMillis = intent?.getLongExtra(EXTRA_ENDS_AT_MILLIS, 0L) ?: 0L
        if (!startAsForeground(endsAtMillis)) stopSelf()
        return START_NOT_STICKY
    }

    private fun startAsForeground(endsAtMillis: Long): Boolean = try {
        createNotificationChannel()
        val notification = buildNotification(endsAtMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w(LOG_TAG, "포그라운드 서비스를 시작하지 못했어요: ${e.javaClass.simpleName}")
        false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "쉼표 휴식", NotificationManager.IMPORTANCE_LOW).apply {
            description = "휴식이 진행 중일 때 표시돼요."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(endsAtMillis: Long): Notification {
        val openFlow = PendingIntent.getActivity(
            this,
            0,
            Intent(this, NapFlowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val endText = if (endsAtMillis > 0) {
            SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(endsAtMillis)) + "에 깨워 드릴게요."
        } else {
            "휴식 중이에요."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("쉼표 · 휴식 중")
            .setContentText(endText)
            .setContentIntent(openFlow)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val LOG_TAG = "ShimpyoTimerService"
        private const val CHANNEL_ID = "shimpyo_rest"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.hyundaitest.nap.START"
        private const val ACTION_STOP = "com.example.hyundaitest.nap.STOP"
        private const val EXTRA_ENDS_AT_MILLIS = "endsAtMillis"

        fun start(context: Context, endsAtMillis: Long) {
            val intent = Intent(context, NapTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENDS_AT_MILLIS, endsAtMillis)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // 예: 앱이 백그라운드일 때 시작 제한. 휴식은 계속된다.
                Log.w(LOG_TAG, "서비스 시작이 막혔어요: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, NapTimerService::class.java))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "서비스 정지 실패: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** RestKeepAlive 의 안드로이드 구현: NapTimerService 를 켜고 끈다. */
class ServiceRestKeepAlive(context: Context) : RestKeepAlive {
    private val appContext = context.applicationContext
    override fun startKeepingAlive(endsAtMillis: Long) = NapTimerService.start(appContext, endsAtMillis)
    override fun stopKeepingAlive() = NapTimerService.stop(appContext)
}

/** NapEventLogger 의 안드로이드 구현 (Logcat 태그: ShimpyoNap) */
class LogcatNapEventLogger : NapEventLogger {
    override fun log(message: String) {
        Log.i("ShimpyoNap", message)
    }
}
