package com.example.hyundaitest.nap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 알람 소리 (앱 자체 소리). 차량 스피커로 나온다.
 *
 * - 기기의 기본 알람음을 쓰고, 없으면 기본 알림음, 그것도 없으면 삐 소리(ToneGenerator)로 대신한다.
 * - 소리를 30% 크기에서 시작해 몇 초에 걸쳐 100%까지 키운다 (설계서 5장 ②).
 * - 앱은 차량 전체 볼륨을 바꿀 수 없다 (S-09). 볼륨 안내는 화면에서 한다.
 * - 어떤 경우에도 예외로 앱이 멈추지 않게 한다.
 */
class AndroidAlarmSound(context: Context) : AlarmSound {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var volumeStep = 0

    override fun startRinging() {
        mainHandler.post {
            stopRingingNow()
            if (!playRingtone()) playFallbackBeep()
        }
    }

    override fun stopRinging() {
        mainHandler.post { stopRingingNow() }
    }

    private fun playRingtone(): Boolean = try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val tone = uri?.let { RingtoneManager.getRingtone(appContext, it) }
        if (tone == null) {
            false
        } else {
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            tone.isLooping = true
            tone.volume = START_VOLUME
            tone.play()
            ringtone = tone
            volumeStep = 0
            mainHandler.postDelayed(raiseVolume, VOLUME_STEP_MILLIS)
            true
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "기본 알람음을 재생하지 못했어요: ${e.javaClass.simpleName}")
        false
    }

    /** 몇 초에 걸쳐 소리를 조금씩 키운다. */
    private val raiseVolume = object : Runnable {
        override fun run() {
            val tone = ringtone ?: return
            volumeStep++
            val volume = (START_VOLUME + volumeStep * VOLUME_INCREMENT).coerceAtMost(1f)
            try {
                tone.volume = volume
            } catch (e: Exception) {
                return
            }
            if (volume < 1f) mainHandler.postDelayed(this, VOLUME_STEP_MILLIS)
        }
    }

    private fun playFallbackBeep() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                it.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, FALLBACK_BEEP_MILLIS)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "대체 알람음도 재생하지 못했어요: ${e.javaClass.simpleName}")
        }
    }

    private fun stopRingingNow() {
        mainHandler.removeCallbacks(raiseVolume)
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "알람 정지 실패: ${e.javaClass.simpleName}")
        }
        ringtone = null
        toneGenerator?.release()
        toneGenerator = null
    }

    private companion object {
        const val LOG_TAG = "ShimpyoAlarm"
        const val START_VOLUME = 0.3f
        const val VOLUME_INCREMENT = 0.1f
        const val VOLUME_STEP_MILLIS = 1_500L
        const val FALLBACK_BEEP_MILLIS = 30_000
    }
}
