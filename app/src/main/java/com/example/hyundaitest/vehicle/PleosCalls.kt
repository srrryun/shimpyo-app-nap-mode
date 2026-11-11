package com.example.hyundaitest.vehicle

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos SDK 의 콜백 함수(onComplete / onFailed)를 코루틴에서 기다리기 위한 도구.
 *
 * 사용 예)
 *   val result = awaitPleos<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) }
 *   result.valueOrNull()
 */
sealed class PleosResult<out T> {
    data class Success<T>(val value: T) : PleosResult<T>()
    data class Failure(val error: Exception) : PleosResult<Nothing>()
    data object TimedOut : PleosResult<Nothing>()

    fun valueOrNull(): T? = (this as? Success<T>)?.value

    /** 화면·로그용 짧은 설명 (내용이 아니라 결과 종류만) */
    fun describe(): String = when (this) {
        is Success -> "성공"
        is Failure -> "${error.javaClass.simpleName}: ${error.message ?: "(메시지 없음)"}"
        TimedOut -> "응답 없음"
    }
}

const val PLEOS_DEFAULT_TIMEOUT_MILLIS = 3_000L

/** 콜백이 오기를 최대 timeoutMillis 동안 기다린다. SDK 가 예외를 던져도 Failure 로 돌려준다. */
suspend fun <T> awaitPleos(
    timeoutMillis: Long = PLEOS_DEFAULT_TIMEOUT_MILLIS,
    call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
): PleosResult<T> = withTimeoutOrNull(timeoutMillis) {
    suspendCancellableCoroutine<PleosResult<T>> { continuation ->
        try {
            call(
                { value -> if (continuation.isActive) continuation.resume(PleosResult.Success(value)) },
                { error -> if (continuation.isActive) continuation.resume(PleosResult.Failure(error)) },
            )
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(PleosResult.Failure(e))
        }
    }
} ?: PleosResult.TimedOut
