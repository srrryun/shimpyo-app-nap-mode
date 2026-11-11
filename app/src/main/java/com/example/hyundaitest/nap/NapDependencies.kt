package com.example.hyundaitest.nap

/*
 * NapController 가 필요로 하는 바깥 기능들의 약속.
 * 안드로이드 기능(시계·소리·서비스·로그)을 인터페이스로 분리해서
 * NapController 를 안드로이드 없이 단위 테스트할 수 있게 한다.
 */

/** 현재 시각 (테스트에서는 가짜 시계로 바꾼다) */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = TimeSource { System.currentTimeMillis() }
    }
}

/** 알람 소리 */
interface AlarmSound {
    fun startRinging()
    fun stopRinging()
}

/** 휴식 중 앱이 꺼지지 않게 붙잡아 두는 장치 (안드로이드에서는 포그라운드 서비스) */
interface RestKeepAlive {
    fun startKeepingAlive(endsAtMillis: Long)
    fun stopKeepingAlive()
}

/**
 * 상태 변화 기록. 디버깅용.
 * 주의: 대나무숲 내용은 절대 넘기지 않는다 (P-04).
 */
fun interface NapEventLogger {
    fun log(message: String)

    companion object {
        val NONE = NapEventLogger { }
    }
}
