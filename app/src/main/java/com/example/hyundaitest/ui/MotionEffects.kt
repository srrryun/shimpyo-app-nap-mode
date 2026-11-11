package com.example.hyundaitest.ui

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 쉼표 화면의 반복 움직임 모음 (3단계 UI 모션).
 *
 * - 모두 "시작하지 않은" Animator 를 돌려준다. 화면이 start() 하고, 화면이 바뀔 때 cancel() 한다.
 * - 시스템 설정에서 애니메이션을 끈 사람(멀미·시각 민감)에게는 null 을 돌려줘서 움직이지 않는다.
 * - 운전 중에 쓰는 화면이 아니고 정차 중 휴식 화면이라, 깨우는 순간에만 눈에 띄는 움직임을 쓴다.
 *
 * 움직임을 바꾸고 싶으면 여기 숫자(크기·시간)만 고치면 된다.
 */
object MotionEffects {

    private fun isEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** 숨 쉬기: 6초에 한 번 천천히 커졌다 작아진다 (휴식 중 로고, 알람 화면 로고) */
    fun breathe(target: View): Animator? {
        if (!isEnabled()) return null
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1f),
        ).apply {
            duration = 3_000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    /** 천천히 돌기: 2.4초에 한 바퀴, 살짝 커졌다 작아지며 (로딩 화면 로고) */
    fun spin(target: View): Animator? {
        if (!isEnabled()) return null
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 360f),
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(0.5f, 1.06f),
                Keyframe.ofFloat(1f, 1f),
            ),
            PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(0.5f, 1.06f),
                Keyframe.ofFloat(1f, 1f),
            ),
        ).apply {
            duration = 2_400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    /** 은은한 맥박: 버튼이 아주 살짝 커졌다 작아진다 ("깨어났어요" 버튼) */
    fun gentlePulse(target: View): Animator? {
        if (!isEnabled()) return null
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f),
        ).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    /** 두근두근: 두 번 빠르게 뛰고 쉰다 (일찍 깨움의 "!" 표시) */
    fun heartbeat(target: View): Animator? {
        if (!isEnabled()) return null
        fun beat() = arrayOf(
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.10f, 1.18f),
            Keyframe.ofFloat(0.20f, 1f),
            Keyframe.ofFloat(0.30f, 1.12f),
            Keyframe.ofFloat(0.40f, 1f),
            Keyframe.ofFloat(1f, 1f),
        )
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofKeyframe(View.SCALE_X, *beat()),
            PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *beat()),
        ).apply {
            duration = 1_400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    /**
     * 흔들기: 짧게 좌우로 흔들고 잠깐 멈췄다가 다시 흔든다 ("일찍 깨웠어요" 제목).
     * 계속 흔들리면 어지러우니 2.6초 중 앞쪽 0.8초만 움직인다.
     */
    fun wiggle(target: View): Animator? {
        if (!isEnabled()) return null
        val rotation = PropertyValuesHolder.ofKeyframe(
            View.ROTATION,
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(0.05f, -4f),
            Keyframe.ofFloat(0.10f, 4f),
            Keyframe.ofFloat(0.15f, -3f),
            Keyframe.ofFloat(0.20f, 3f),
            Keyframe.ofFloat(0.25f, -1.5f),
            Keyframe.ofFloat(0.30f, 0f),
            Keyframe.ofFloat(1f, 0f),
        )
        val scale = arrayOf(
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.10f, 1.06f),
            Keyframe.ofFloat(0.30f, 1f),
            Keyframe.ofFloat(1f, 1f),
        )
        return ObjectAnimator.ofPropertyValuesHolder(
            target,
            rotation,
            PropertyValuesHolder.ofKeyframe(View.SCALE_X, *scale),
            PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *scale),
        ).apply {
            duration = 2_600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }
}
