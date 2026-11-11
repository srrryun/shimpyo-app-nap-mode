package com.example.hyundaitest.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.R
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.data.model.RestPreset
import com.example.hyundaitest.nap.NapPlan
import com.example.hyundaitest.nap.NapStartRequest
import com.example.hyundaitest.nap.StartNapResult
import com.example.hyundaitest.nap.WakeStep
import com.example.hyundaitest.safety.ParkingReading
import com.example.hyundaitest.safety.ParkingState
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.ComfortItem
import kotlinx.coroutines.launch

/**
 * 시작 확인 (3단계 UI, 설계서 화면 5) — 스크롤 없이 한 화면에 들어가게.
 *
 *   위: ← · "가벼운 낮잠 20분" · "오후 1:20쯤 깨워 드릴게요"
 *   가운데(남는 높이를 채움): 왼쪽 "준비된 것"(차가 해 줄 일·대나무숲·주차) | 오른쪽 "이렇게 깨워 드려요"
 *   아래: 가장 큰 "쉬기 시작" 버튼 (+ 작은 "다시 고르기")
 * 알려주는 정보는 작게, 누르는 버튼은 크게 (11/11 사용자 의견).
 *
 * 시작 순서(1단계·v1.1 규칙 그대로):
 *  1. 주차 상태를 자동으로 읽는다.
 *  2. 읽지 못했으면(UNKNOWN) "정차 중이에요"를 한 번 확인받는다.
 *  3. 안전 관문(SafetyGuard)이 최종 판단한다. 주차 아님이면 여기서 막힌다.
 */
class StartConfirmActivity : AppCompatActivity() {

    private val container by lazy { appContainer }
    private val draft get() = container.restDraft

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var readyCard: LinearLayout
    private lateinit var wakeCard: LinearLayout
    private lateinit var messageText: TextView

    private var plan: NapPlan? = null
    private var isStarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = shimpyoScreen(gapDp = 24)

        // 위: 뒤로 + 제목
        val header = row(24)
        header.addView(iconButton(R.drawable.ic_shimpyo_back, "뒤로") { finish() })
        val titles = column(6)
        titleText = shimpyoText("준비하는 중…", ShimpyoText.TITLE)
        subtitleText = shimpyoText("", ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY)
        titles.addView(titleText)
        titles.addView(subtitleText)
        header.addView(titles.weighted(1f))
        screen.addView(header.fillWidth())

        // 가운데: 준비된 것 | 이렇게 깨워 드려요
        // "준비된 것"은 내용만큼만 작게, "이렇게 깨워 드려요"는 넓고 크게 (11/11 사용자 의견)
        val middle = row(28, centerVertical = false).apply { isBaselineAligned = false }
        readyCard = card(gapDp = 24, paddingDp = 28)
        wakeCard = card(color = ShimpyoColors.TINT, gapDp = 8, paddingDp = 32)
        middle.addView(readyCard.weighted(0.8f))
        middle.addView(wakeCard.weighted(1.6f))
        screen.addView(spacer(weight = 1f))
        screen.addView(middle.fillWidth())
        screen.addView(spacer(weight = 1f))

        messageText = shimpyoText("", ShimpyoText.BODY_STRONG, ShimpyoColors.DANGER).apply { gravity = Gravity.CENTER }
        screen.addView(messageText.fillWidth())

        // 아래: 버튼
        val buttons = row(24)
        buttons.addView(shimpyoButton("다시 고르기", ButtonKind.GHOST, heightDp = START_BUTTON_HEIGHT) { finish() }.fixedWidth(280))
        buttons.addView(
            shimpyoButton("쉬기 시작", heightDp = START_BUTTON_HEIGHT) { startRest() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f) // 이 화면의 주인공 버튼이라 가장 크게
                typeface = ShimpyoFonts.get(this@StartConfirmActivity, FontWeight.EXTRA_BOLD)
                background = pressableDrawable(ShimpyoColors.NAVY, 40)
                // 남는 너비를 다 쓰면서 높이도 그대로 두껍게 (weighted() 는 높이를 글자 높이로 줄여서 쓰지 않는다)
                layoutParams = LinearLayout.LayoutParams(0, dp(START_BUTTON_HEIGHT), 1f)
            },
        )
        screen.addView(buttons.fillWidth())
    }

    override fun onResume() {
        super.onResume()
        if (container.napController.state.value.isRestInProgress) {
            startActivity(Intent(this, NapFlowActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch { prepareSummary() }
    }

    // ------------------------------------------------------------------
    // 요약
    // ------------------------------------------------------------------

    private suspend fun prepareSummary() {
        val supported = if (container.appSettings.isComfortControlEnabled) {
            container.restEnvironment.findSupportedItems()
        } else {
            emptySet()
        }
        val approved = draft.approvedOrAll(supported)
        val newPlan = if (draft.isTestRun) {
            NapPlan.testRun(approvedComfortItems = approved)
        } else {
            selectedPreset()?.let { NapPlan.fromPreset(it, approved) }
        }
        plan = newPlan
        val reading = container.parkingStatusReader.readParkingStatus()
        showSummary(newPlan, approved, reading)
    }

    private suspend fun selectedPreset(): RestPreset? {
        val presets = container.presetRepository.loadPresets()
        val selectedId = container.presetRepository.loadSelectedPresetId() ?: RestPreset.ID_LIGHT
        return presets.firstOrNull { it.id == selectedId }
    }

    private fun showSummary(plan: NapPlan?, approved: Set<ComfortItem>, reading: ParkingReading) {
        readyCard.removeAllViews()
        wakeCard.removeAllViews()
        if (plan == null) {
            titleText.text = "휴식 종류를 다시 골라 주세요"
            subtitleText.text = ""
            return
        }
        val testLabel = if (plan.isTestRun) " (테스트)" else ""
        titleText.text = "${plan.presetName} ${plan.durationMillis / SafetyRules.MINUTE}분$testLabel"
        subtitleText.text = "${formatClock(System.currentTimeMillis() + plan.durationMillis)}쯤 천천히 깨워 드릴게요"

        // 준비된 것
        readyCard.addView(shimpyoText("준비된 것", ShimpyoText.BODY_STRONG))
        val comfortText = if (approved.isEmpty()) {
            "차는 그대로 둬요"
        } else {
            ComfortItem.entries.filter { it in approved }.joinToString(" · ") { comfortLabel(it) }
        }
        readyCard.addView(infoLine(R.drawable.ic_shimpyo_settings, "차가 해 줄 일", comfortText).fillWidth())
        readyCard.addView(infoLine(R.drawable.ic_shimpyo_lock, "대나무숲", if (draft.hasBambooText) "고민을 맡겨요" else "맡기지 않아요").fillWidth())
        readyCard.addView(
            infoLine(R.drawable.ic_shimpyo_car, "주차 상태", parkingLabel(reading.state), parkingColor(reading.state)).fillWidth(),
        )

        // 이렇게 깨워 드려요
        fillWakeCard(plan, approved)

        messageText.text = if (reading.state == ParkingState.NOT_PARKED) {
            "P단에 두고 파킹브레이크를 걸어 주세요. 주차 상태일 때만 쉴 수 있어요."
        } else {
            ""
        }
    }

    /** 작은 아이콘 · 이름(작게) · 값 한 덩어리 */
    private fun infoLine(iconRes: Int, label: String, value: String, valueColor: Int = ShimpyoColors.TEXT): LinearLayout =
        row(18).apply {
            addView(iconTile(iconRes).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
            val texts = column(2)
            texts.addView(shimpyoText(label, ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            texts.addView(shimpyoText(value, ShimpyoText.BODY_STRONG, valueColor))
            addView(texts.weighted(1f))
        }

    /**
     * 깨우는 순서를 시간 순으로. 승인한 항목의 단계만 나온다.
     * 시점은 실제 휴식과 같은 계산(WakeStep.scheduleFor)을 써서, 1분 테스트면 "30초 전"처럼 줄어든 값이 나온다.
     */
    private fun fillWakeCard(plan: NapPlan, approved: Set<ComfortItem>) {
        val rules = container.safetyRules
        wakeCard.addView(shimpyoText("이렇게 깨워 드려요", ShimpyoText.HEADLINE).apply { setPadding(0, 0, 0, dp(12)) })
        val schedule = WakeStep.scheduleFor(
            steps = WakeStep.defaultSteps(),
            restDurationMillis = plan.durationMillis,
            fullSequenceMinRestMillis = rules.fullWakeSequenceMinRestMillis,
        )
        for (scheduled in schedule) {
            val item = comfortItemFor(scheduled.step.id) ?: continue
            if (item !in approved) continue
            wakeCard.addView(timelineRow(beforeText(scheduled.millisBeforeEnd), wakeStepText(scheduled.step.id)).fillWidth())
            wakeCard.addView(arrowRow())
        }
        wakeCard.addView(
            timelineRow("끝나면", "알람이 울려요. 누를 때까지 1분마다 다시 울려요 (최대 ${rules.alarmMaxRings}번)", highlight = true)
                .fillWidth(),
        )
        wakeCard.addView(
            shimpyoText("알람은 차량 볼륨으로 울려요. 소리가 너무 작지 않은지 확인해 주세요.", ShimpyoText.BODY_STRONG, ShimpyoColors.PERIWINKLE_DEEP)
                .apply { setPadding(0, dp(16), 0, 0) },
        )
    }

    /** 왼쪽 시간 알약 + 오른쪽 설명 한 줄 (크게) */
    private fun timelineRow(time: String, text: String, highlight: Boolean = false): LinearLayout = row(22).apply {
        addView(
            shimpyoText(time, ShimpyoText.BODY_STRONG, if (highlight) android.graphics.Color.WHITE else ShimpyoColors.TEXT).apply {
                gravity = Gravity.CENTER
                background = roundedDrawable(if (highlight) ShimpyoColors.NAVY else ShimpyoColors.SURFACE, 999)
                setPadding(0, dp(14), 0, dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(TIME_PILL_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
        addView(shimpyoText(text, ShimpyoText.BODY_STRONG).weighted(1f))
    }

    /** 단계 사이 아래 화살표 (시간 알약 가운데 아래에 맞춤) */
    private fun arrowRow(): LinearLayout = row().apply {
        addView(android.widget.ImageView(this@StartConfirmActivity).apply {
            setImageResource(R.drawable.ic_shimpyo_arrow_down)
            imageTintList = android.content.res.ColorStateList.valueOf(ShimpyoColors.PERIWINKLE)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(TIME_PILL_WIDTH), dp(30))
        })
    }

    private fun comfortItemFor(wakeStepId: String): ComfortItem? = when (wakeStepId) {
        "light_up" -> ComfortItem.AMBIENT_LIGHT
        "fresh_air" -> ComfortItem.CLIMATE
        "raise_seat" -> ComfortItem.SEAT_BACKREST
        else -> null
    }

    private fun wakeStepText(wakeStepId: String): String = when (wakeStepId) {
        "light_up" -> "조명이 서서히 밝아져요"
        "fresh_air" -> "바깥 공기가 들어오고 조금 시원해져요"
        "raise_seat" -> "등받이가 천천히 일어나요"
        else -> ""
    }

    /** 300000 → "5분 전", 30000 → "30초 전" */
    private fun beforeText(millisBeforeEnd: Long): String {
        val seconds = millisBeforeEnd / SafetyRules.SECOND
        return if (seconds >= 60) "${seconds / 60}분 전" else "${seconds}초 전"
    }

    // ------------------------------------------------------------------
    // 시작
    // ------------------------------------------------------------------

    private fun startRest() {
        val plan = plan ?: return
        if (isStarting) return
        isStarting = true
        messageText.text = ""
        lifecycleScope.launch {
            val reading = container.parkingStatusReader.readParkingStatus()
            if (reading.state == ParkingState.UNKNOWN) {
                askUserToConfirmParked(
                    onConfirmed = { requestNapStart(plan, reading.state, isParkedConfirmedByUser = true) },
                    onCancelled = { isStarting = false },
                )
            } else {
                requestNapStart(plan, reading.state, isParkedConfirmedByUser = false)
            }
        }
    }

    private fun requestNapStart(plan: NapPlan, parkingState: ParkingState, isParkedConfirmedByUser: Boolean) {
        val request = NapStartRequest(
            plan = plan,
            bambooText = draft.bambooText,
            isParkedConfirmedByUser = isParkedConfirmedByUser,
            parkingState = parkingState,
        )
        lifecycleScope.launch {
            try {
                when (val result = container.napController.startNap(request)) {
                    is StartNapResult.Started -> {
                        draft.clearAfterStart()
                        startActivity(Intent(this@StartConfirmActivity, NapFlowActivity::class.java))
                        finish()
                    }
                    is StartNapResult.Blocked -> messageText.text = result.reasons.joinToString("\n") { it.message }
                }
            } finally {
                isStarting = false
            }
        }
    }

    /** 자동 확인이 안 될 때만 뜨는 한 번 확인 창 */
    private fun askUserToConfirmParked(onConfirmed: () -> Unit, onCancelled: () -> Unit) {
        showShimpyoDialog(
            title = "차가 서 있나요?",
            message = "차 상태를 자동으로 확인하지 못했어요. P단에 두고 파킹브레이크를 건 상태에서만 쉬어 주세요.",
            confirmText = "정차 중이에요",
            dismissText = "취소",
            onDismiss = onCancelled,
            onConfirm = onConfirmed,
        )
    }

    private companion object {
        /** 쉬기 시작 버튼 높이 (dp). 화면에서 가장 크게 */
        const val START_BUTTON_HEIGHT = 160
        const val TIME_PILL_WIDTH = 150
    }
}
