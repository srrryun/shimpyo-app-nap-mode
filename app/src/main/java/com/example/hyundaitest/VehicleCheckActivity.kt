package com.example.hyundaitest

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.vehicle.VehicleParkingStatusReader
import kotlinx.coroutines.launch

/**
 * 개발자용 읽기 전용 점검 화면.
 * 낮잠 모드에 필요한 차량 기능이 이 에뮬레이터(차량)에서 지원되는지
 * check...Capabilities 로만 조회한다. 차량 상태를 바꾸는 set/move 호출은 하지 않는다.
 */
class VehicleCheckActivity : AppCompatActivity() {

    private class Check(
        val group: String,
        val name: String,
        val permission: String,
        val run: (ok: (CapabilityConfig, Any?) -> Unit, fail: (Exception) -> Unit) -> Unit
    )

    private class Row(val access: TextView, val detail: TextView)

    private lateinit var vehicle: Vehicle
    private lateinit var statusView: TextView
    private lateinit var table: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controlContainer: LinearLayout
    private var controlPanel: ControlTestPanel? = null
    private var runId = 0
    private lateinit var parkingResultText: TextView
    private val parkingReader by lazy {
        VehicleParkingStatusReader(this, vehicleProvider = { if (::vehicle.isInitialized) vehicle else null })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        vehicle = Vehicle(applicationContext)
        try {
            vehicle.initialize()
            statusView.text = "Vehicle SDK 초기화 완료 · 점검 중…"
            runChecks()
            controlPanel = ControlTestPanel(this, vehicle).also { controlContainer.addView(it.build()) }
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed", e)
            statusView.text = "Vehicle SDK 초기화 실패: ${e.javaClass.simpleName}: ${e.message}"
            statusView.setTextColor(COLOR_ERROR)
        }
        readParkingStatus()
    }

    /** 주차 상태 읽기 시험: 파킹브레이크·기어를 읽을 수 있는 곳을 모두 시도해서 하나씩 보여준다 (읽기만 함) */
    private fun readParkingStatus() {
        parkingResultText.text = "읽는 중…"
        parkingResultText.setTextColor(Color.DKGRAY)
        lifecycleScope.launch {
            val reading = parkingReader.readParkingStatus()
            val lines = reading.sources.map { source ->
                val mark = when (source.value) {
                    true -> "주차 쪽(true)"
                    false -> "주차 아님(false)"
                    null -> "읽지 못함"
                }
                "• ${source.sourceName}: $mark — ${source.note}"
            }
            parkingResultText.text = (listOf("최종 판단: ${reading.state.label} (${reading.state.name})") + lines).joinToString("\n")
            parkingResultText.setTextColor(if (reading.state.name == "UNKNOWN") COLOR_WARN else COLOR_OK)
            Log.i(TAG, "PARKING | ${reading.state} | " + lines.joinToString(" | "))
        }
    }

    override fun onPause() {
        controlPanel?.stopAll()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controlPanel?.stopAll()
        try {
            vehicle.release()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        super.onDestroy()
    }

    private fun checks(): List<Check> {
        val seat = vehicle.getSeat()
        val hvac = vehicle.getHVAC()
        val light = vehicle.getLight()
        val window = vehicle.getWindow()
        val door = vehicle.getDoor()
        val brake = vehicle.getBrake()
        val battery = vehicle.getEvBattery()
        return listOf(
            Check("시트", "등받이 각도 (위치값)", "CAR_SEATS") { ok, fail ->
                seat.checkSeatBackrestAnglePositionCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("시트", "등받이 각도 (이동)", "CAR_SEATS") { ok, fail ->
                seat.checkMoveSeatBackrestAngleCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("시트", "앞뒤 위치", "CAR_SEATS") { ok, fail ->
                seat.checkSeatForeAftPositionCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("시트", "착석 감지", "CAR_SEATS") { ok, fail ->
                seat.checkSeatOccupancyCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("시트", "열선", "CAR_SEATS") { ok, fail ->
                seat.checkSeatTemperatureCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("시트", "통풍", "CAR_SEATS") { ok, fail ->
                seat.checkSeatVentilationCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("공조", "전원", "CAR_CLIMATE") { ok, fail ->
                hvac.checkHvacPowerCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("공조", "설정 온도", "CAR_CLIMATE") { ok, fail ->
                hvac.checkHvacTemperatureCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("공조", "풍량", "CAR_CLIMATE") { ok, fail ->
                hvac.checkHvacFanSpeedCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("공조", "내기 순환", "CAR_CLIMATE") { ok, fail ->
                hvac.checkHvacRecirculationCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("공조", "오토", "CAR_CLIMATE") { ok, fail ->
                hvac.checkHvacAutoCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("조명", "실내등 상태", "CAR_LIGHTS") { ok, fail ->
                light.checkCabinLightStateCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("조명", "실내등 스위치", "CAR_LIGHTS") { ok, fail ->
                light.checkCabinLightSwitchCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("조명", "무드등 밝기", "CAR_LIGHTS") { ok, fail ->
                light.checkAmbientLightBrightnessCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("조명", "무드등 스위치", "CAR_LIGHTS") { ok, fail ->
                light.checkAmbientLightSwitchCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("창문", "창문 위치", "CAR_WINDOWS") { ok, fail ->
                window.checkWindowPositionCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("창문", "블라인드 위치", "CAR_WINDOWS") { ok, fail ->
                window.checkWindowBlindPositionCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("창문", "블라인드 이동", "CAR_WINDOWS") { ok, fail ->
                window.checkMoveWindowBlindCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("창문", "스마트 글라스 단계", "CAR_WINDOWS") { ok, fail ->
                window.checkSmartGlassLevelCapabilities(onComplete = { c, m -> ok(c, m) }, onFailed = fail)
            },
            Check("도어", "문 잠금", "CAR_DOORS") { ok, fail ->
                door.checkDoorLockCapabilities(onComplete = { c, l -> ok(c, l) }, onFailed = fail)
            },
            Check("브레이크", "브레이크 페달", "CAR_DRIVE") { ok, fail ->
                brake.checkBrakePedalStatusCapabilities(onComplete = { c -> ok(c, null) }, onFailed = fail)
            },
            Check("배터리", "충전 잔량(SoC)", "CAR_ENERGY") { ok, fail ->
                battery.checkEvBatteryStateOfChargeCapabilities(onComplete = { c -> ok(c, null) }, onFailed = fail)
            }
        )
    }

    private fun runChecks() {
        val id = ++runId
        table.removeAllViews()
        addRow(listOf("분류", "기능", "필요 권한", "접근", "지원 영역 · 값 범위 / 오류"), header = true)
        val list = checks()
        var done = 0
        var readWrite = 0
        var readOnly = 0
        var failed = 0
        val finishOne = { result: String ->
            done++
            when (result) {
                "READ_WRITE" -> readWrite++
                "READ" -> readOnly++
                else -> failed++
            }
            statusView.text = "점검 $done / ${list.size} · 읽기+제어 $readWrite · 읽기만 $readOnly · 미지원·오류 $failed"
            if (done == list.size) Log.i(TAG, "SUMMARY | 읽기+제어 $readWrite | 읽기만 $readOnly | 미지원·오류 $failed")
        }
        for (check in list) {
            val row = addRow(listOf(check.group, check.name, check.permission, "대기 중", ""), header = false)
            var answered = false
            val ok: (CapabilityConfig, Any?) -> Unit = { config, extra ->
                runOnUiThread {
                    if (id == runId && !answered) {
                        answered = true
                        val supported = config.accessType != AccessType.NONE
                        row.access.text = config.accessType.name
                        row.access.setTextColor(if (supported) COLOR_OK else COLOR_WARN)
                        row.detail.text = if (extra == null) "-" else fmt(extra)
                        Log.i(TAG, "RESULT | ${check.group} | ${check.name} | ${config.accessType.name} | ${row.detail.text.toString().replace("\n", " / ")}")
                        finishOne(config.accessType.name)
                    }
                }
            }
            val fail: (Exception) -> Unit = { e ->
                Log.w(TAG, "${check.group}/${check.name} failed", e)
                runOnUiThread {
                    if (id == runId && !answered) {
                        answered = true
                        row.access.text = "오류"
                        row.access.setTextColor(COLOR_ERROR)
                        row.detail.text = "${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}"
                        row.detail.setTextColor(COLOR_ERROR)
                        Log.i(TAG, "RESULT | ${check.group} | ${check.name} | 오류 | ${row.detail.text}")
                        finishOne("ERROR")
                    }
                }
            }
            try {
                check.run(ok, fail)
            } catch (e: Exception) {
                fail(e)
            }
            handler.postDelayed({
                if (id == runId && !answered) {
                    answered = true
                    row.access.text = "응답 없음"
                    row.access.setTextColor(COLOR_WARN)
                    row.detail.text = "${TIMEOUT_MS / 1000}초 안에 응답이 없었어요"
                    Log.i(TAG, "RESULT | ${check.group} | ${check.name} | 응답 없음 | -")
                    finishOne("TIMEOUT")
                }
            }, TIMEOUT_MS)
        }
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "-"
        is Map<*, *> -> if (v.isEmpty()) "지원 영역 없음" else v.entries.joinToString("\n") { "${fmt(it.key)}: ${fmt(it.value)}" }
        is List<*> -> if (v.isEmpty()) "지원 영역 없음" else v.joinToString(", ") { fmt(it) }
        is AreaBounds<*> -> "${v.minValue} ~ ${v.maxValue}"
        is SeatArea -> v.values.joinToString("+") { it.name }
        is WindowArea -> v.values.joinToString("+") { it.name }
        is DoorArea -> v.values.joinToString("+") { it.name }
        is Enum<*> -> v.name
        else -> v.toString()
    }

    private fun buildLayout() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // 차 화면에는 뒤로 가기 버튼이 없을 수 있어서 직접 둔다
        root.addView(Button(this).apply {
            text = "← 쉼표로 돌아가기"
            textSize = 22f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "차량 기능 점검 (개발자용 · 읽기 전용)"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "check…Capabilities 조회만 해요. 차량 상태를 바꾸지 않아요. 결과는 Logcat(VehicleCheck)에도 남아요."
            textSize = 20f
        })
        statusView = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)
        root.addView(Button(this).apply {
            text = "다시 점검"
            textSize = 22f
            setOnClickListener { runChecks() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(table)
        root.addView(TextView(this).apply {
            text = "주차 상태 읽기 시험 (파킹브레이크 · 기어)"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Virtual Console에서 파킹브레이크·기어를 바꾼 뒤 아래 버튼을 눌러, 값이 따라 바뀌는지 확인해요. 읽기만 해요."
            textSize = 20f
        })
        root.addView(Button(this).apply {
            text = "주차 상태 다시 읽기"
            textSize = 22f
            setOnClickListener { readParkingStatus() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parkingResultText = TextView(this).apply {
            textSize = 22f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(parkingResultText)
        controlContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(controlContainer)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addRow(cells: List<String>, header: Boolean): Row {
        val weights = floatArrayOf(1f, 2f, 2f, 1.5f, 5f)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val views = cells.mapIndexed { i, s ->
            TextView(this).apply {
                text = s
                textSize = 22f
                if (header) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), 0, dp(4), 0)
            }.also { line.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i])) }
        }
        table.addView(line)
        return Row(access = views[3], detail = views[4])
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "VehicleCheck"
        private const val TIMEOUT_MS = 5000L
        private val COLOR_OK = Color.parseColor("#2E7D32")
        private val COLOR_WARN = Color.parseColor("#EF6C00")
        private val COLOR_ERROR = Color.parseColor("#C62828")
    }
}
