package com.example.hyundaitest.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 1단계 임시 화면용 작은 도구들. 3단계(UI/UX)에서 디자인을 입힐 때 이 파일을 바꾸거나 대체한다.
 * 차량 화면(2560×1440)에서 잘 보이도록 글씨와 버튼을 크게 둔다.
 */

object UiSize {
    const val TITLE = 34f
    const val BODY = 24f
    const val SMALL = 18f
    const val BUTTON = 24f
    const val HUGE = 72f
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.isDebugBuild(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** 세로로 쌓이는 스크롤 화면. 반환값(LinearLayout)에 내용을 추가한다. */
fun Context.verticalScrollScreen(setContent: (ScrollView) -> Unit): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(24), dp(32), dp(24))
    }
    setContent(ScrollView(this).apply { addView(column) })
    return column
}

fun Context.titleText(text: String) = TextView(this).apply {
    this.text = text
    textSize = UiSize.TITLE
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(8), 0, dp(8))
}

fun Context.bodyText(text: String = "", size: Float = UiSize.BODY) = TextView(this).apply {
    this.text = text
    textSize = size
    setPadding(0, dp(6), 0, dp(6))
}

fun Context.bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    textSize = UiSize.BUTTON
    setPadding(dp(24), dp(16), dp(24), dp(16))
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) }
}

/**
 * 누르면 한 번, 길게 누르고 있으면 계속 반복 실행한다. (예: 휴식 시간 +1분 / −1분)
 * 손을 떼면 바로 멈춘다.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.repeatWhileHeld(intervalMillis: Long = 120L, action: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val repeater = object : Runnable {
        override fun run() {
            action()
            handler.postDelayed(this, intervalMillis)
        }
    }
    setOnClickListener { action() }
    setOnLongClickListener {
        handler.post(repeater)
        true
    }
    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(repeater)
        }
        false
    }
}

fun Context.horizontalRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** 예: 12분 30초 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
}

/** 예: 오후 3:20 */
fun formatClock(millis: Long): String = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(millis))
