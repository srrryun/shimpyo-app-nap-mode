package com.example.hyundaitest.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hyundaitest.R
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.data.model.BambooNote
import com.example.hyundaitest.data.model.NapSession
import com.example.hyundaitest.data.model.StopReason
import com.example.hyundaitest.nap.NapState
import com.example.hyundaitest.vehicle.ControlResult
import com.example.hyundaitest.vehicle.ControlStatus
import com.example.hyundaitest.vehicle.VehicleAlert
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 휴식 진행 화면 (3단계 UI). NapController.state 하나만 보고 상태별로 다시 그린다.
 *
 *   Resting       → 휴식 중 (어두운 남색, 큰 남은 시간, 숨 쉬는 쉼표)
 *   AlarmRinging  → 일어날 시간 (밝은 화면, 큰 "깨어났어요")
 *   AwakeCare     → 깨어남 케어 (복귀 체크 · 차량 상태 · 복원 결과 · 맡겨둔 이야기)
 *   Stopped       → 중단
 *   Completed     → 휴식 완료
 *   Idle          → 화면 닫기
 *
 */
class NapFlowActivity : AppCompatActivity() {

    private val container by lazy { appContainer }
    private val controller get() = container.napController

    private lateinit var content: LinearLayout
    private var remainingTimeTicker: Job? = null
    /** 지금 화면에서 돌고 있는 반복 움직임 (화면이 바뀌면 모두 멈춘다) */
    private val runningAnimators = mutableListOf<android.animation.Animator>()

    // 대나무숲 표시 상태 (화면을 다시 그려도 유지)
    private var revealedBambooNoteId: String? = null
    private var bambooDecisionMessage: String? = null

    /** 슬립 모드: 화면을 덮은 검은 막 (null 이면 화면이 켜져 있음) */
    private var sleepOverlay: View? = null
    private var sleepTimer: Job? = null

    /** 깨어남 안내가 차례로 나타나는 움직임을 이미 보여준 휴식 (같은 휴식에서는 한 번만) */
    private var awakeIntroPlayedFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = shimpyoScreen()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { state -> showState(state) }
            }
        }
        // 휴식 환경 세팅 결과는 휴식 시작 몇 초 뒤에 도착한다 → 휴식 화면이면 다시 그린다
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.environmentResults.collect {
                    val state = controller.state.value
                    if (state is NapState.Resting) showState(state)
                }
            }
        }
    }

    /**
     * 다른 앱으로 가거나 앱을 닫으면: 쉬는 중이 아니면 휴식을 정리하고 이 화면을 닫는다.
     * 다음에 앱을 열면 처음 화면(홈)부터 시작한다. 쉬는 중에는 그대로 둔다(알람이 울려야 하므로).
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val state = controller.state.value
        if (state is NapState.Resting || state == NapState.Idle) return
        container.appScope.launch { controller.closeAfterUserLeft() }
        finish()
    }

    /**
     * 화면을 만질 때마다 슬립 타이머를 다시 잰다.
     * 슬립 중이면 첫 터치는 화면을 켜기만 하고 버튼에는 전달하지 않는다 (어둠 속에서 "멈추기"를 잘못 누르지 않게).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (sleepOverlay != null) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                exitSleep()
                scheduleSleep()
            }
            return true
        }
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && controller.state.value is NapState.Resting) scheduleSleep()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        exitSleep()
        stopAllAnimations()
        super.onDestroy()
    }

    private fun showState(state: NapState) {
        remainingTimeTicker?.cancel()
        stopAllAnimations()
        content.removeAllViews()
        if (state !is NapState.Resting) exitSleep() // 알람·중단 등: 화면을 바로 켠다
        val isNight = state is NapState.Resting
        content.setScreenBackground(
            when (state) {
                is NapState.Resting -> ShimpyoColors.NIGHT
                is NapState.Stopped -> ShimpyoColors.DANGER_BACKGROUND
                is NapState.AlarmRinging -> if (state.earlyWakeReason != null) ShimpyoColors.DANGER_BACKGROUND else ShimpyoColors.BACKGROUND
                else -> ShimpyoColors.BACKGROUND
            },
        )
        // 상태 표시줄 색은 최신 안드로이드에서 앱 바탕이 비쳐 보이는 방식이라(statusBarColor 는 쓰지 않음),
        // 아이콘 밝기만 바탕에 맞춘다: 어두운 휴식 화면이면 흰 아이콘, 밝은 화면이면 어두운 아이콘.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight && !ShimpyoTheme.isDark
        when (state) {
            NapState.Idle -> finish()
            is NapState.Resting -> showResting(state)
            is NapState.AlarmRinging -> showAlarmRinging(state)
            is NapState.AwakeCare -> showAwakeCare(state)
            is NapState.Stopped -> showStopped(state)
            is NapState.Completed -> showCompleted(state)
        }
    }

    // ------------------------------------------------------------------
    // 휴식 중 (어두운 화면)
    // ------------------------------------------------------------------

    private fun showResting(state: NapState.Resting) {
        val session = state.session

        val top = row(16)
        top.addView(shimpyoText(sessionLabel(session), ShimpyoText.LABEL, ShimpyoColors.NIGHT_TEXT_SECONDARY).weighted(1f))
        val watchText = if (container.vehicle != null) "차를 지켜보고 있어요" else "타이머로 지켜보고 있어요"
        top.addView(pill(watchText, ShimpyoColors.NIGHT_SURFACE, ShimpyoColors.NIGHT_TEXT_SECONDARY, dotColor = ShimpyoColors.NIGHT_OK))
        content.addView(top.fillWidth())

        content.addView(spacer(weight = 1f))

        val center = column(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = logoView(150, night = true)
        center.addView(logo)
        val remainingText = shimpyoText("", ShimpyoText.TIMER, ShimpyoColors.NIGHT_TEXT).apply { gravity = Gravity.CENTER }
        center.addView(remainingText)
        center.addView(
            shimpyoText("${formatClock(session.endsAtMillis)}에 천천히 깨워 드릴게요", ShimpyoText.HEADLINE, ShimpyoColors.NIGHT_TEXT_SECONDARY)
                .apply { gravity = Gravity.CENTER },
        )
        state.wakeGuidance?.let {
            center.addView(shimpyoText(it, ShimpyoText.BODY, ShimpyoColors.NIGHT_TEXT).apply { gravity = Gravity.CENTER })
        }
        content.addView(center.fillWidth())

        content.addView(spacer(weight = 1f))

        val bottom = row(16)
        val results = column(10)
        for (result in controller.environmentResults.value) {
            results.addView(
                pill("${result.itemName} · ${result.status.label}", ShimpyoColors.NIGHT_SURFACE, controlStatusColor(result.status, night = true)),
            )
        }
        bottom.addView(results.weighted(1f))
        bottom.addView(shimpyoButton("휴식 멈추기", ButtonKind.NIGHT_GHOST) { askStopRest() })
        content.addView(bottom.fillWidth())

        if (isDebugBuild()) addVehicleEventSimulator()

        startBreathing(logo)
        if (sleepOverlay == null && sleepTimer?.isActive != true) scheduleSleep()
        remainingTimeTicker = lifecycleScope.launch {
            while (isActive) {
                remainingText.text = formatCountdown(session.endsAtMillis - System.currentTimeMillis())
                delay(TICK_MILLIS)
            }
        }
    }

    /** 로고가 숨 쉬듯 천천히 커졌다 작아진다 */
    private fun startBreathing(target: View) {
        keepAnimating(MotionEffects.breathe(target))
    }

    private fun stopAllAnimations() {
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }

    // ------------------------------------------------------------------
    // 슬립 모드 (쉬는 동안 화면 끄기)
    //  - 휴대폰·게임기의 절전 화면처럼: 검은 막 + 화면 밝기 최저. 앱은 계속 돌아서 알람은 제때 울린다.
    //  - 화면 자체를 끄는 기능(전원)은 일반 앱이 못 쓰므로, 이 앱 창의 밝기만 낮춘다.
    // ------------------------------------------------------------------

    private fun scheduleSleep() {
        sleepTimer?.cancel()
        if (!container.appSettings.isSleepModeEnabled) return
        sleepTimer = lifecycleScope.launch {
            delay(SLEEP_AFTER_SECONDS * 1_000L)
            if (controller.state.value is NapState.Resting) enterSleep()
        }
    }

    private fun enterSleep() {
        sleepTimer?.cancel()
        if (sleepOverlay != null || controller.state.value !is NapState.Resting) return
        val hint = shimpyoText("화면을 누르면 다시 켜져요", ShimpyoText.HEADLINE, ShimpyoColors.NIGHT_TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(ShimpyoColors.SLEEP_DIM)
            contentDescription = "화면 꺼짐. 누르면 다시 켜져요"
            alpha = 0f
            addView(hint)
        }
        addContentView(
            overlay,
            android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT),
        )
        sleepOverlay = overlay
        overlay.animate().alpha(1f).setDuration(SLEEP_FADE_MILLIS).start()
        hint.animate().alpha(0f).setStartDelay(SLEEP_HINT_MILLIS).setDuration(SLEEP_FADE_MILLIS).start()
        setWindowBrightness(SLEEP_BRIGHTNESS)
    }

    private fun exitSleep() {
        sleepTimer?.cancel()
        val overlay = sleepOverlay ?: return
        sleepOverlay = null
        overlay.animate().cancel()
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun setWindowBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun askStopRest() {
        sleepTimer?.cancel()
        showShimpyoDialog(
            title = "휴식을 멈출까요?",
            message = "차를 원래대로 되돌릴지는 다음 화면에서 고를 수 있어요.",
            confirmText = "멈추기",
            dismissText = "계속 쉬기",
            danger = true,
        ) { controller.stopNapNow(StopReason.USER_PRESSED_STOP) }
            .setOnDismissListener { if (controller.state.value is NapState.Resting) scheduleSleep() }
    }

    /**
     * 개발용(디버그 빌드만): 차량 변화를 흉내 내서 앱 반응을 확인하는 버튼.
     * 실제 차량 값은 바뀌지 않는다. SDK 로 값을 읽는 부분은 Virtual Console 로 따로 확인한다.
     */
    private fun addVehicleEventSimulator() {
        content.addView(shimpyoText("개발용 · 차량 변화 흉내 (실제 차량 값은 바뀌지 않아요)", ShimpyoText.CAPTION, ShimpyoColors.NIGHT_TEXT_SECONDARY))
        val buttons = row(12)
        listOf(
            VehicleAlert.DOOR_OPENED to "문 열림",
            VehicleAlert.DOOR_UNLOCKED to "문 잠금 풀림",
            VehicleAlert.BRAKE_PRESSED to "브레이크",
            VehicleAlert.PARKING_RELEASED to "주차 풀림",
            VehicleAlert.BATTERY_LOW to "배터리 부족",
        ).forEach { (alert, label) ->
            buttons.addView(shimpyoButton(label, ButtonKind.NIGHT_GHOST, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
                container.vehicleMonitor.simulate(alert)
            })
        }
        content.addView(buttons)
    }

    // ------------------------------------------------------------------
    // 일어날 시간 (알람)
    // ------------------------------------------------------------------

    /**
     * 일어날 시간 (알람).
     * - 정상 종료: 밝은 화면, 로고가 숨 쉬고 "깨어났어요" 버튼이 살짝 커졌다 작아진다.
     * - 일찍 깨움(배터리 부족 등): 중단 화면처럼 연한 빨간 바탕 + "!" 표시가 두근거리고,
     *   "일찍 깨웠어요" 제목이 잠깐씩 흔들려 눈길을 끈다.
     * 화면이 처음 나타날 때는 아래에서 위로 부드럽게 떠오른다.
     */
    private fun showAlarmRinging(state: NapState.AlarmRinging) {
        val early = state.earlyWakeReason
        content.addView(spacer(weight = 1f))
        val center = centeredColumn(maxWidthDp = 1500, gapDp = 28)

        val topMark: View = if (early != null) {
            dangerMark()
        } else {
            logoView(140)
        }
        center.addView(topMark)

        val heading = titleBlock(
            if (early == null) "일어날 시간이에요" else "일찍 깨웠어요",
            early?.message ?: "${formatClock(state.session.endsAtMillis)}에 휴식을 마쳤어요. 천천히 눈을 떠 보세요.",
            centered = true,
            titleColor = if (early != null) ShimpyoColors.DANGER else null,
        )
        center.addView(heading.fillWidth())

        if (state.hasGivenUp) {
            center.addView(
                shimpyoText("알람을 멈췄어요. 준비되면 아래 버튼을 눌러 주세요.", ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY)
                    .apply { gravity = Gravity.CENTER }
                    .fillWidth(),
            )
        }
        val awakeButton = shimpyoButton("깨어났어요", heightDp = 112) { controller.confirmAwake() }.fixedWidth(480)
        center.addView(awakeButton)
        content.addView(center)
        content.addView(spacer(weight = 1f))

        // 움직임
        playEntrance(center)
        if (early != null) {
            keepAnimating(MotionEffects.heartbeat(topMark))
            keepAnimating(MotionEffects.wiggle(heading.getChildAt(0)))
        } else {
            keepAnimating(MotionEffects.breathe(topMark))
            keepAnimating(MotionEffects.gentlePulse(awakeButton))
        }
    }

    /** 연한 빨간 원 안의 "!" (중단·일찍 깨움 공통) */
    private fun dangerMark(): TextView = shimpyoText("!", ShimpyoText.TITLE, ShimpyoColors.DANGER).apply {
        gravity = Gravity.CENTER
        background = roundedDrawable(ShimpyoColors.DANGER_TINT, 48)
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 화면이 처음 나타날 때 아래에서 살짝 떠오르며 보이게 */
    private fun playEntrance(target: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        target.alpha = 0f
        target.translationY = dp(32).toFloat()
        target.animate().alpha(1f).translationY(0f).setDuration(ENTRANCE_MILLIS).start()
    }

    /** 반복 움직임을 등록해 두고, 화면이 바뀌면 한꺼번에 멈춘다 */
    private fun keepAnimating(animator: android.animation.Animator?) {
        if (animator == null) return
        runningAnimators += animator
        animator.start()
    }

    // ------------------------------------------------------------------
    // 깨어남 케어
    // ------------------------------------------------------------------

    /**
     * 깨어남 케어 — 화면 높이를 꽉 채운다.
     *   왼쪽: "다시 움직이기 전에" 안내가 한 줄씩 차례로 나타나고 체크 표시가 톡 튀어나온다 (누를 필요 없음).
     *         마지막 줄은 지금 차량 상태.
     *   오른쪽: 원래대로 되돌린 결과 · 맡겨둔 이야기
     *   아래: 넓은 "휴식 마치기"
     * 차례로 나타나는 움직임은 이 휴식에서 처음 한 번만 (복원 결과가 도착해 다시 그릴 때는 바로 보여준다).
     */
    private fun showAwakeCare(state: NapState.AwakeCare) {
        val session = state.session
        val restedMillis = minOf(state.awakeConfirmedAtMillis, session.endsAtMillis) - session.startedAtMillis
        val playIntro = awakeIntroPlayedFor != session.id && ValueAnimator.areAnimatorsEnabled()
        awakeIntroPlayedFor = session.id

        content.addView(
            titleBlock(
                "잘 쉬었어요?",
                "${formatDuration(restedMillis)} 쉬었어요 · ${formatClock(state.awakeConfirmedAtMillis)}에 일어났어요. 천천히 준비해요.",
                centered = true,
            ).fillWidth(),
        )

        val columns = row(28, centerVertical = false).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // 왼쪽: 다시 움직이기 전에 (차례로 나타남)
        val checklist = card(gapDp = 0)
        checklist.addView(shimpyoText("다시 움직이기 전에", ShimpyoText.HEADLINE))
        val rows = mutableListOf<LinearLayout>()
        RETURN_GUIDANCE.forEach { line ->
            checklist.addView(spacer(weight = 1f))
            val r = guidanceRow(R.drawable.ic_shimpyo_check, line)
            rows += r
            checklist.addView(r.fillWidth())
        }
        checklist.addView(spacer(weight = 1f))
        val signalRow = guidanceRow(R.drawable.ic_shimpyo_car, "차량 상태를 확인하는 중…")
        rows += signalRow
        checklist.addView(signalRow.fillWidth())
        checklist.addView(spacer(weight = 1f))
        lifecycleScope.launch {
            val signal = container.vehicleSignalReader.readSignal()
            val text = signalRow.getChildAt(1) as TextView
            text.text = if (signal.isVehicleConnected) signal.summary() else "차량 상태는 확인하지 못했어요"
        }
        columns.addView(checklist.weighted(1f, matchHeight = true))

        // 오른쪽: 복원 결과 · 맡겨둔 이야기
        val right = column(28)
        controlResultsCard("원래대로 되돌렸어요", state.restoreResults)?.let { right.addView(it.fillWidth()) }
        bambooCard(session)?.let { right.addView(it.fillWidth()) }
        if (right.childCount == 0) {
            val calm = card(color = ShimpyoColors.TINT, gapDp = 16)
            calm.addView(logoView(96))
            calm.addView(shimpyoText("차는 그대로였어요", ShimpyoText.HEADLINE))
            calm.addView(shimpyoText("쉬는 동안 바꾼 게 없어서 되돌릴 것도 없어요.", ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY))
            right.addView(calm.fillWidth())
        }
        columns.addView(right.weighted(1f))
        content.addView(columns)

        content.addView(shimpyoButton("휴식 마치기", heightDp = 120) { finishRestAfterBambooCheck(session) }.fillWidth(dp(120)))

        if (playIntro) {
            rows.forEachIndexed { index, rowView -> revealRow(rowView, index) }
        }
    }

    /** 안내 한 줄: 남색 동그라미 아이콘 + 큰 글씨 (누르지 않는 표시용) */
    private fun guidanceRow(iconRes: Int, line: String): LinearLayout = row(22).apply {
        val mark = iconTile(iconRes, ShimpyoColors.NAVY, Color.WHITE)
        mark.layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        mark.background = roundedDrawable(ShimpyoColors.NAVY, 30)
        addView(mark)
        addView(shimpyoText(line, ShimpyoText.BODY_STRONG).weighted(1f))
        contentDescription = line
    }

    /** 한 줄이 왼쪽에서 미끄러져 들어오고, 동그라미 아이콘이 톡 튀어나온다 */
    private fun revealRow(rowView: LinearLayout, index: Int) {
        val delay = AWAKE_INTRO_FIRST_DELAY_MILLIS + index * AWAKE_INTRO_STEP_MILLIS
        rowView.alpha = 0f
        rowView.translationX = -dp(48).toFloat()
        rowView.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(420L).start()
        val mark = rowView.getChildAt(0)
        mark.scaleX = 0f
        mark.scaleY = 0f
        mark.animate().scaleX(1f).scaleY(1f).setStartDelay(delay + 180L).setDuration(380L)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
    }

    // ------------------------------------------------------------------
    // 중단
    // ------------------------------------------------------------------

    /** 중단: 연한 빨간 바탕에 가운데 정렬. 이유를 먼저 크게 보여준다. */
    private fun showStopped(state: NapState.Stopped) {
        val session = state.session
        val restedMillis = minOf(state.stoppedAtMillis, session.endsAtMillis) - session.startedAtMillis

        content.addView(spacer(weight = 1f))
        val center = centeredColumn(maxWidthDp = 1500, gapDp = 28)
        center.addView(dangerMark())
        // 글은 가운데 한 덩어리로만: 큰 제목 + 이유 + 쉰 시간
        center.addView(
            shimpyoText("휴식을 멈췄어요", ShimpyoText.DISPLAY, ShimpyoColors.DANGER).apply { gravity = Gravity.CENTER }.fillWidth(),
        )
        center.addView(
            shimpyoText(
                "${state.reason.message}\n${formatDuration(restedMillis)} 쉬었어요.",
                ShimpyoText.HEADLINE,
                ShimpyoColors.TEXT_SECONDARY,
            ).apply { gravity = Gravity.CENTER }.fillWidth(),
        )
        controlResultsCard("되돌린 결과", state.restoreResults)?.let { center.addView(it.fillWidth()) }
        bambooCard(session)?.let { center.addView(it.fillWidth()) }
        content.addView(center)
        content.addView(spacer(weight = 1f))

        // 버튼은 화면 아래에 한 줄로 (되돌리기는 아직 안 했을 때만)
        val buttons = row(24)
        if (container.restEnvironment.isVehicleControlEnabled && state.restoreResults.isEmpty()) {
            buttons.addView(
                shimpyoButton("차 원래대로 되돌리기", ButtonKind.SECONDARY, STOPPED_BUTTON_HEIGHT_DP) {
                    controller.restoreVehicleAfterStop()
                }.weightedFixedHeight(1f, STOPPED_BUTTON_HEIGHT_DP),
            )
        }
        buttons.addView(
            shimpyoButton("휴식 마치기", ButtonKind.PRIMARY, STOPPED_BUTTON_HEIGHT_DP) { finishRestAfterBambooCheck(session) }
                .weightedFixedHeight(1f, STOPPED_BUTTON_HEIGHT_DP),
        )
        content.addView(buttons.fillWidth())
        if (state.restoreResults.isEmpty() && bambooDecisionMessage == null && revealedBambooNoteId == null) playEntrance(center)
    }

    // ------------------------------------------------------------------
    // 휴식 완료
    // ------------------------------------------------------------------

    /**
     * 휴식 완료 — 앱 영역을 가로로 꽉 채운다 (옆은 Pleos 차량 화면).
     *   위: 로고 · 큰 제목 · 언제부터 언제까지 쉬었는지
     *   가운데: 큰 숫자 카드 3개 — 쉰 시간 · 이번 주 · 일어난 시각
     *   아래: 화면 너비만큼 큰 "홈으로" 버튼
     */
    private fun showCompleted(state: NapState.Completed) {
        val record = state.record
        val stopped = record.stopReason

        val top = row(16)
        top.addView(logoView(96))
        top.addView(shimpyoText("쉼표", ShimpyoText.HEADLINE))
        content.addView(top)

        content.addView(spacer(weight = 1f))
        content.addView(
            titleBlock(
                if (stopped == null) "오늘의 쉼표가 끝났어요" else "오늘의 쉼표를 여기까지 했어요",
                "${formatClock(record.startedAtMillis)}부터 ${formatClock(record.endedAtMillis)}까지 쉬었어요",
            ).fillWidth(),
        )

        val stats = row(24, centerVertical = false).apply { isBaselineAligned = false }
        stats.addView(statCard("쉰 시간", restLengthText(record.actualRestMillis), record.presetName).weighted(1f, matchHeight = true))
        val weekly = statCard("이번 주", "…")
        stats.addView(weekly.weighted(1f, matchHeight = true))
        stats.addView(statCard("마친 시각", formatClock(record.endedAtMillis), if (stopped == null) "예정대로 마쳤어요" else "중간에 멈췄어요").weighted(1f, matchHeight = true))
        content.addView(stats.fillWidth())

        if (stopped != null) {
            content.addView(
                pill("중간에 멈췄어요 · ${stopped.message}", ShimpyoColors.DANGER_BACKGROUND, ShimpyoColors.DANGER, dotColor = ShimpyoColors.DANGER),
            )
        }

        content.addView(spacer(weight = 1f))
        content.addView(shimpyoButton("홈으로", heightDp = 120) { goHome() }.fillWidth(dp(120)))

        // 이번 주 기록은 저장소에서 읽어 채운다 (개발용 테스트 휴식은 빼고 센다)
        lifecycleScope.launch {
            val since = System.currentTimeMillis() - WEEK_MILLIS
            val records = container.restHistoryRepository.loadRecords().filter { it.endedAtMillis >= since && !it.isTestRun }
            val value = weekly.getChildAt(1) as TextView
            val sub = weekly.getChildAt(2) as TextView
            if (records.isEmpty()) {
                value.text = "첫 쉼표"
                sub.text = "테스트 휴식은 세지 않아요"
            } else {
                value.text = "${records.size}번"
                sub.text = "모두 ${records.sumOf { it.actualRestMillis } / com.example.hyundaitest.safety.SafetyRules.MINUTE}분 쉬었어요"
            }
        }
    }

    /** 이름 · 큰 숫자 · 한 줄 설명 카드 (완료 화면) */
    private fun statCard(label: String, value: String, sub: String = ""): LinearLayout = card(gapDp = 12).apply {
        addView(shimpyoText(label, ShimpyoText.BODY_STRONG, ShimpyoColors.TEXT_SECONDARY))
        addView(shimpyoText(value, ShimpyoText.DISPLAY))
        addView(shimpyoText(sub, ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY))
    }

    /** 20분 → "20분", 45초 → "45초", 12분 30초 → "12분 30초" */
    private fun restLengthText(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes == 0L -> "${seconds}초"
            seconds == 0L -> "${minutes}분"
            else -> "${minutes}분 ${seconds}초"
        }
    }

    private fun goHome() {
        controller.returnToIdle()
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------------
    // 대나무숲 (버튼을 눌러야만 내용을 보여준다)
    // ------------------------------------------------------------------

    private fun bambooCard(session: NapSession): LinearLayout? {
        val noteId = session.bambooNoteId ?: return null
        val cardView = card(color = ShimpyoColors.TINT, gapDp = 18, shadow = false)

        val header = row(18)
        header.addView(iconTile(R.drawable.ic_shimpyo_lock, ShimpyoColors.SURFACE))
        val texts = column(6)
        cardView.addView(header.fillWidth())

        bambooDecisionMessage?.let {
            texts.addView(shimpyoText("대나무숲", ShimpyoText.BODY_STRONG))
            texts.addView(shimpyoText(it, ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            header.addView(texts.weighted(1f))
            return cardView
        }
        if (revealedBambooNoteId != noteId) {
            texts.addView(shimpyoText("맡겨둔 이야기가 있어요", ShimpyoText.BODY_STRONG))
            texts.addView(shimpyoText("누르기 전에는 보이지 않아요", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            header.addView(texts.weighted(1f))
            header.addView(shimpyoButton("확인하기", ButtonKind.GHOST, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
                revealedBambooNoteId = noteId
                showState(controller.state.value)
            })
            return cardView
        }
        texts.addView(shimpyoText("맡겨둔 이야기", ShimpyoText.BODY_STRONG))
        header.addView(texts.weighted(1f))
        val body = column(18)
        cardView.addView(body.fillWidth())
        lifecycleScope.launch {
            val note = container.bambooRepository.findNote(noteId)
            if (note == null) {
                body.addView(shimpyoText("이미 지운 이야기예요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            } else {
                showBambooNote(body, note)
            }
        }
        return cardView
    }

    private fun showBambooNote(body: LinearLayout, note: BambooNote) {
        body.addView(shimpyoText(note.text, ShimpyoText.BODY).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, container.appSettings.memoTextSize.sp)
            background = roundedDrawable(ShimpyoColors.SURFACE, 20)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }.fillWidth())
        if (note.isKept) {
            body.addView(shimpyoText("기록에 보관된 이야기예요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            return
        }
        val buttons = row(14)
        buttons.addView(shimpyoButton("지우기", ButtonKind.GHOST, ShimpyoSize.BUTTON_HEIGHT_SMALL) { deleteBambooNote(note.id) })
        buttons.addView(shimpyoButton("보관하기", ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) { keepBambooNote(note.id) })
        body.addView(buttons)
    }

    private fun deleteBambooNote(noteId: String, then: (() -> Unit)? = null) {
        lifecycleScope.launch {
            container.bambooRepository.deleteNote(noteId)
            bambooDecisionMessage = "맡겨둔 이야기를 지웠어요."
            showState(controller.state.value)
            then?.invoke()
        }
    }

    private fun keepBambooNote(noteId: String, then: (() -> Unit)? = null) {
        lifecycleScope.launch {
            container.bambooRepository.markNoteAsKept(noteId)
            bambooDecisionMessage = "기록에 보관했어요. 홈의 '보관한 이야기'에서 다시 볼 수 있어요."
            showState(controller.state.value)
            then?.invoke()
        }
    }

    /** 아직 지우기/보관을 고르지 않은 이야기가 있으면 먼저 묻는다 (기본 선택: 지우기, D-4). */
    private fun finishRestAfterBambooCheck(session: NapSession) {
        lifecycleScope.launch {
            val note = session.bambooNoteId?.let { container.bambooRepository.findNote(it) }
            if (note == null || note.isKept) {
                controller.finishRest()
                return@launch
            }
            showShimpyoDialog(
                title = "맡겨둔 이야기를 어떻게 할까요?",
                message = "고르지 않은 이야기는 지우는 것을 추천해요.",
                confirmText = "지우기",
                dismissText = "보관하기",
                cancelable = false,
                onDismiss = { keepBambooNote(note.id) { finishRestNow() } },
            ) { deleteBambooNote(note.id) { finishRestNow() } }
        }
    }

    private fun finishRestNow() {
        lifecycleScope.launch { controller.finishRest() }
    }

    // ------------------------------------------------------------------
    // 공통
    // ------------------------------------------------------------------

    private fun finishButtonRow(session: NapSession): LinearLayout = row(20).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(shimpyoButton("휴식 마치기") { finishRestAfterBambooCheck(session) }.fixedWidth(360))
    }.fillWidth()

    /** 제어 결과 4가지(요청함 / 반영 확인 / 반영 안 됨 / 미지원)를 항목별로 보여주는 카드. 결과가 없으면 null */
    private fun controlResultsCard(title: String, results: List<ControlResult>): LinearLayout? {
        if (results.isEmpty()) return null
        val cardView = card(gapDp = 16)
        cardView.addView(shimpyoText(title, ShimpyoText.HEADLINE))
        for (result in results) {
            cardView.addView(infoRow(result.itemName, result.status.label, controlStatusColor(result.status)).fillWidth())
        }
        if (results.any { it.status == ControlStatus.NOT_APPLIED }) {
            cardView.addView(
                shimpyoText("요청은 보냈지만 차량 값이 바뀌지 않은 항목이 있어요. (에뮬레이터에서는 정상)", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
            )
        }
        return cardView
    }

    private fun sessionLabel(session: NapSession): String {
        val testLabel = if (session.isTestRun) " · 테스트" else ""
        return "${session.presetName} · ${formatDuration(session.plannedDurationMillis)}$testLabel"
    }

    /** 남은 시간을 "17:42" 처럼 */
    private fun formatCountdown(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val TICK_MILLIS = 500L
        const val ENTRANCE_MILLIS = 450L
        const val AWAKE_INTRO_FIRST_DELAY_MILLIS = 300L
        const val AWAKE_INTRO_STEP_MILLIS = 450L
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** 설계서 5장 ④ 단계별 복귀 안내. 운전이나 차량 조작을 권유하지 않는다. */
        val RETURN_GUIDANCE = listOf(
            "천천히 주변을 확인해 보세요",
            "필요한 물건을 챙겨요",
            "다음 일정을 떠올려 봐요",
        )
    }
}

private const val STOPPED_BUTTON_HEIGHT_DP = 120

private const val SLEEP_FADE_MILLIS = 1_200L
/** 슬립 중 창 밝기 (0=가장 어둡게, 1=가장 밝게). 꺼진 듯하지만 남은 시간은 희미하게 보이는 정도 */
private const val SLEEP_BRIGHTNESS = 0.05f
private const val SLEEP_HINT_MILLIS = 3_000L
