package com.example.hyundaitest

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sign

/**
 * 개발자용 제어 테스트. 사용자가 버튼을 누를 때만 차량을 제어한다.
 * - 무드등 밝기: 절대값 설정(setAmbientLightBrightness) 후 다시 읽어 반영 여부 확인, "원래대로"로 처음 값 복원
 * - 운전석 등받이: 위치 설정 API가 없어 moveSeatBackrestAngle(BACKWARD/FORWARD/STOP)로 움직인다.
 *   테스트는 2초만 움직이고 반드시 STOP. 복원은 처음 위치에 닿을 때까지 반대로 움직이고 STOP(최대 15초).
 */
class ControlTestPanel(
    private val activity: AppCompatActivity,
    private val vehicle: Vehicle
) {
    private val handler = Handler(Looper.getMainLooper())
    private val seatArea = SeatArea(SeatValue.ROW_1_LEFT)

    private var ambientArea: SeatArea? = null
    private var ambientRange: AreaBounds<Int>? = null
    private var ambientOriginal: Int? = null

    private var seatOriginal: Int? = null
    private var backwardSign: Int? = null
    private var seatMoving = false
    private var restoring = false

    private lateinit var ambientStatus: TextView
    private lateinit var seatStatus: TextView
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(text("제어 테스트 (버튼을 누를 때만 실행)", 28f, bold = true))
        root.addView(text("값을 바꾼 뒤 다시 읽어서 실제로 반영됐는지 확인해요. '원래대로'는 처음 읽은 값으로 되돌려요.", 18f))

        root.addView(text("무드등 밝기", 24f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        ambientStatus = text("준비 중…", 22f)
        root.addView(ambientStatus)
        root.addView(buttons(
            "현재값 읽기" to { readAmbient("읽기") },
            "50으로 설정" to { setAmbientTest() },
            "원래대로" to { restoreAmbient() }
        ))

        root.addView(text("운전석 등받이 각도 (ROW_1_LEFT)", 24f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        seatStatus = text("현재값 읽기를 눌러 주세요", 22f)
        root.addView(seatStatus)
        root.addView(buttons(
            "현재값 읽기" to { readSeat { v -> seatStatus.text = "현재 위치: ${v ?: "-"}" + originalText() } },
            "뒤로 2초 젖히기" to { seatTest() },
            "원래대로" to { restoreSeat() },
            "정지" to { stopSeat("사용자 정지") }
        ))

        root.addView(text("제어 기록", 22f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        logView = text("", 18f)
        root.addView(logView)

        initAmbient()
        return root
    }

    /** 화면이 가려지거나 꺼질 때 시트가 움직이고 있으면 반드시 멈춘다. */
    fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        if (seatMoving) stopSeat("화면 종료")
    }

    // ---------- 무드등 ----------

    private fun initAmbient() {
        vehicle.getLight().checkAmbientLightBrightnessCapabilities(
            onComplete = { _, map ->
                ui {
                    val first = map.entries.firstOrNull()
                    if (first == null) {
                        ambientStatus.text = "지원 영역이 없어요"
                    } else {
                        ambientArea = first.key
                        ambientRange = first.value
                        readAmbient("처음 값")
                    }
                }
            },
            onFailed = { e -> ui { ambientStatus.text = "확인 실패: ${msg(e)}"; log("무드등 확인 실패: ${msg(e)}") } }
        )
    }

    private fun readAmbient(reason: String, then: ((Int?) -> Unit)? = null) {
        val area = ambientArea ?: run { ambientStatus.text = "아직 지원 영역을 못 읽었어요"; return }
        vehicle.getLight().getAmbientLightBrightness(
            area = area,
            onComplete = { v ->
                ui {
                    if (ambientOriginal == null && v != null) ambientOriginal = v
                    ambientStatus.text = "현재 밝기: ${v ?: "-"} · 처음 값: ${ambientOriginal ?: "-"} · 범위: ${ambientRange?.minValue}~${ambientRange?.maxValue}"
                    log("무드등 $reason → ${v ?: "-"}")
                    then?.invoke(v)
                }
            },
            onFailed = { e -> ui { ambientStatus.text = "읽기 실패: ${msg(e)}"; log("무드등 읽기 실패: ${msg(e)}") } }
        )
    }

    private fun setAmbientTest() {
        val target = if (ambientOriginal == 50) 20 else 50
        setAmbient(target, "테스트 설정")
    }

    private fun restoreAmbient() {
        val original = ambientOriginal ?: run { log("무드등: 처음 값이 없어서 복원할 수 없어요"); return }
        setAmbient(original, "원래대로")
    }

    private fun setAmbient(value: Int, reason: String) {
        val area = ambientArea ?: run { log("무드등: 지원 영역을 아직 못 읽었어요"); return }
        val range = ambientRange
        val v = if (range == null) value else value.coerceIn(range.minValue, range.maxValue)
        log("무드등 $reason: $v 요청")
        vehicle.getLight().setAmbientLightBrightness(
            area = area,
            value = v,
            onComplete = { _, accepted ->
                ui {
                    log("무드등 설정 응답: $accepted")
                    handler.postDelayed({
                        readAmbient("다시 읽기") { now ->
                            log(if (now == v) "무드등 반영 확인: $v ✔" else "무드등 반영 안 됨: 요청 $v, 읽은 값 ${now ?: "-"}")
                        }
                    }, 700)
                }
            },
            onFailed = { e -> ui { log("무드등 설정 실패: ${msg(e)}") } }
        )
    }

    // ---------- 운전석 등받이 ----------

    private fun readSeat(then: (Int?) -> Unit) {
        vehicle.getSeat().getCurrentSeatBackrestAnglePosition(
            area = seatArea,
            onComplete = { v -> ui { then(v) } },
            onFailed = { e -> ui { seatStatus.text = "읽기 실패: ${msg(e)}"; log("등받이 읽기 실패: ${msg(e)}") } }
        )
    }

    private fun seatTest() {
        if (seatMoving) { log("등받이가 이미 움직이는 중이에요"); return }
        readSeat { before ->
            if (seatOriginal == null) seatOriginal = before
            log("등받이 테스트 시작: 지금 ${before ?: "-"}")
            move(MoveControl.BACKWARD)
            handler.postDelayed({
                stopSeat("2초 경과")
                handler.postDelayed({
                    readSeat { after ->
                        if (before != null && after != null) {
                            val s = (after - before).sign
                            if (s != 0) backwardSign = s
                            log(if (s == 0) "등받이 값이 바뀌지 않았어요 ($before → $after)" else "등받이 반영 확인: $before → $after ✔")
                        }
                        seatStatus.text = "현재 위치: ${after ?: "-"}" + originalText()
                    }
                }, 700)
            }, 2000)
        }
    }

    private fun restoreSeat() {
        if (seatMoving) { log("등받이가 움직이는 중이에요. 정지 후 다시 눌러 주세요"); return }
        val original = seatOriginal ?: run { log("등받이: 테스트 전 위치가 없어서 복원할 필요가 없어요"); return }
        readSeat { now ->
            if (now == null) { log("등받이: 현재 위치를 못 읽었어요"); return@readSeat }
            if (now == original) { log("등받이: 이미 원래 위치($original)예요 ✔"); return@readSeat }
            val bs = backwardSign ?: run { log("등받이: 움직이는 방향을 알 수 없어 자동 복원을 멈췄어요"); return@readSeat }
            val needSign = (original - now).sign
            val dir = if (needSign == bs) MoveControl.BACKWARD else MoveControl.FORWARD
            log("등받이 원래대로: $now → $original ($dir)")
            restoring = true
            move(dir)
            val deadline = System.currentTimeMillis() + 15_000
            pollRestore(original, needSign, deadline)
        }
    }

    private fun pollRestore(original: Int, needSign: Int, deadline: Long) {
        handler.postDelayed({
            if (!restoring) return@postDelayed
            readSeat { v ->
                when {
                    v != null && (original - v).sign != needSign -> {
                        stopSeat("원래 위치 도달")
                        handler.postDelayed({
                            readSeat { last ->
                                seatStatus.text = "현재 위치: ${last ?: "-"}" + originalText()
                                log("등받이 복원 결과: ${last ?: "-"} (목표 $original)")
                            }
                        }, 500)
                    }
                    System.currentTimeMillis() > deadline -> stopSeat("15초 초과, 안전 정지")
                    else -> {
                        seatStatus.text = "복원 중… 현재 ${v ?: "-"} → 목표 $original"
                        pollRestore(original, needSign, deadline)
                    }
                }
            }
        }, 200)
    }

    private fun move(dir: MoveControl) {
        seatMoving = true
        vehicle.getSeat().moveSeatBackrestAngle(
            area = seatArea,
            value = dir,
            onComplete = { _, v -> ui { log("등받이 이동 요청 응답: $v") } },
            onFailed = { e -> ui { log("등받이 이동 실패: ${msg(e)}"); stopSeat("이동 실패") } }
        )
    }

    private fun stopSeat(reason: String) {
        restoring = false
        seatMoving = false
        vehicle.getSeat().moveSeatBackrestAngle(
            area = seatArea,
            value = MoveControl.STOP,
            onComplete = { _, _ -> ui { log("등받이 정지 ($reason)") } },
            onFailed = { e -> ui { log("등받이 정지 실패: ${msg(e)}") } }
        )
    }

    // ---------- 공통 ----------

    private fun originalText() = seatOriginal?.let { " · 테스트 전 위치: $it" } ?: ""

    private fun log(line: String) {
        Log.i(TAG, line)
        logLines.addFirst(line)
        while (logLines.size > 12) logLines.removeLast()
        logView.text = logLines.joinToString("\n")
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

    private fun msg(e: Exception) = "${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}"

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buttons(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(Button(activity).apply {
                text = label
                textSize = 20f
                setOnClickListener { action() }
            })
        }
        return row
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "VehicleControl"
    }
}
