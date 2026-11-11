package com.example.hyundaitest.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.R
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.data.model.RestPreset
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.vehicle.ComfortItem
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 홈 (프리셋 선택) — 3단계 UI.
 *
 *  위쪽: 로고 · 차 상태 알약(누르면 차량 상태 화면) · 설정 버튼
 *  제목: 시간대 인사 + 주차 안내 / 오른쪽에 "이번 주 쉼표" 기록
 *  가운데: 프리셋 카드 3개 — 화면 높이를 채운다 (누르면 선택, −/+ 로 1분씩, 길게 누르면 연속)
 *  아래쪽: 쉬는 동안 차가 해 줄 일(켜고 끄기) · 대나무숲 카드 · "N분 쉬기" 버튼 → 시작 확인 화면
 *
 * 여기서는 휴식을 시작하지 않는다. 고른 값은 RestDraft(메모리)와 PresetRepository(저장)에 두고,
 * 실제 시작은 StartConfirmActivity 가 한다.
 */
class HomeActivity : AppCompatActivity() {

    private val container by lazy { appContainer }
    private val draft get() = container.restDraft

    private lateinit var statusChips: LinearLayout
    private lateinit var greetingTitle: TextView
    private lateinit var headlineSub: TextView
    private lateinit var weeklyTitle: TextView
    private lateinit var weeklySub: TextView
    private lateinit var presetRow: LinearLayout
    private lateinit var comfortPills: LinearLayout
    private lateinit var bambooTitle: TextView
    private lateinit var bambooSub: TextView
    private lateinit var startButton: LinearLayout
    private lateinit var startTitle: TextView
    private lateinit var startSub: TextView

    private var presets: List<RestPreset> = emptyList()
    private var selectedPresetId: String = RestPreset.ID_LIGHT
    private var supportedItems: Set<ComfortItem> = emptySet()

    /** 프리셋 카드 하나의 바뀌는 부분 (카드를 다시 만들지 않고 글자만 바꾼다 — 길게 누르는 중에 버튼이 사라지지 않게) */
    private class PresetCardViews(val card: LinearLayout, val minutes: TextView, val badge: TextView)

    private val presetViews = mutableMapOf<String, PresetCardViews>()

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부해도 화면 알람은 울린다 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        showLoadingOverlay()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (container.napController.state.value.isRestInProgress) {
            startActivity(Intent(this, NapFlowActivity::class.java))
            return
        }
        // 다른 앱에 갔다가 앱 아이콘으로 다시 들어오면 로딩 화면을 다시 보여준다
        val app = application as com.example.hyundaitest.ShimpyoApplication
        if (app.consumeReturnedFromBackground() && loadingOverlay == null) showLoadingOverlay()
        greetingTitle.text = greetingFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        lifecycleScope.launch {
            loadPresets()
            refreshBambooCard()
            showWeeklyRecord()
            hideLoadingOverlay() // 화면 뼈대가 채워지면 로딩을 걷는다. 차량 상태는 그 뒤에 이어서 채운다
            showInterruptedNotice()
            refreshComfortItems()
            refreshVehicleStatus()
        }
    }

    // ------------------------------------------------------------------
    // 화면 만들기
    // ------------------------------------------------------------------

    private fun buildScreen() {
        val screen = shimpyoScreen(gapDp = 32)

        // 위쪽 줄
        val top = row(16)
        top.addView(logoView(72))
        top.addView(shimpyoText("쉼표", ShimpyoText.HEADLINE).weighted(1f))
        statusChips = row(12)
        top.addView(statusChips)
        top.addView(iconButton(R.drawable.ic_shimpyo_settings, "설정") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        screen.addView(top.fillWidth())

        // 제목 + 이번 주 기록
        val headerRow = row(28)
        val heading = titleBlock("오늘은 얼마나 쉴까요?", "차 상태를 확인하고 있어요…")
        greetingTitle = heading.getChildAt(0) as TextView
        headlineSub = heading.getChildAt(1) as TextView
        headerRow.addView(heading.weighted(1f))
        val weekly = row(18).apply {
            background = roundedDrawable(ShimpyoColors.TINT, ShimpyoSize.CARD_RADIUS)
            setPadding(dp(28), dp(22), dp(32), dp(22))
        }
        weekly.addView(iconTile(R.drawable.ic_shimpyo_moon, ShimpyoColors.SURFACE))
        val weeklyTexts = column(4)
        weeklyTitle = shimpyoText("이번 주 쉼표", ShimpyoText.BODY_STRONG)
        weeklySub = shimpyoText("", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY)
        weeklyTexts.addView(weeklyTitle)
        weeklyTexts.addView(weeklySub)
        weekly.addView(weeklyTexts)
        headerRow.addView(weekly)
        screen.addView(headerRow.fillWidth())

        // 프리셋 카드 3개 — 남는 높이를 채운다
        presetRow = row(24, centerVertical = false).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            minimumHeight = dp(300)
        }
        screen.addView(presetRow)

        // 아래쪽: 차가 해 줄 일 · 대나무숲 · 시작
        val bottom = row(24, centerVertical = false).apply { isBaselineAligned = false }

        val comfortCard = card(gapDp = 16)
        comfortCard.addView(shimpyoText(COMFORT_SECTION_TITLE, ShimpyoText.BODY_STRONG))
        comfortCard.addView(shimpyoText(COMFORT_SECTION_GUIDE, ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY))
        comfortPills = column(10)
        comfortCard.addView(comfortPills)
        bottom.addView(comfortCard.weighted(1.5f, matchHeight = true))

        val bambooCard = pressableRowCard("대나무숲에 고민 맡기기") {
            startActivity(Intent(this, BambooActivity::class.java))
        }
        bambooCard.addView(iconTile(R.drawable.ic_shimpyo_lock))
        val bambooTexts = column(8)
        bambooTitle = shimpyoText("대나무숲에 고민 맡기기", ShimpyoText.BODY_STRONG)
        bambooSub = shimpyoText("기기에만 암호화해 둬요", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY)
        bambooTexts.addView(bambooTitle)
        bambooTexts.addView(bambooSub)
        bambooCard.addView(bambooTexts.weighted(1f))
        bambooCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shimpyo_chevron)
            imageTintList = ColorStateList.valueOf(ShimpyoColors.TEXT_TERTIARY)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        bottom.addView(bambooCard.weighted(1f, matchHeight = true))

        startButton = column(8).apply {
            gravity = Gravity.CENTER
            background = pressableDrawable(ShimpyoColors.NAVY, ShimpyoSize.CARD_RADIUS)
            setPadding(dp(32), dp(28), dp(32), dp(28))
            isClickable = true
            isFocusable = true
            addPressMotion(0.96f)
            setOnClickListener { goToStartConfirm() }
        }
        startTitle = shimpyoText("쉬기", ShimpyoText.TITLE, Color.WHITE).apply { gravity = Gravity.CENTER }
        startSub = shimpyoText("", ShimpyoText.CAPTION, ShimpyoColors.NIGHT_TEXT_SECONDARY).apply { gravity = Gravity.CENTER }
        startButton.addView(startTitle)
        startButton.addView(startSub)
        bottom.addView(startButton.fixedWidth(380, matchHeight = true))

        screen.addView(bottom.fillWidth())
    }

    // ------------------------------------------------------------------
    // 프리셋
    // ------------------------------------------------------------------

    private suspend fun loadPresets() {
        presets = container.presetRepository.loadPresets()
        selectedPresetId = container.presetRepository.loadSelectedPresetId() ?: RestPreset.ID_LIGHT
        if (presetViews.isEmpty()) buildPresetCards()
        updatePresetViews()
    }

    private fun buildPresetCards() {
        presetRow.removeAllViews()
        presetViews.clear()
        val step = container.safetyRules.restMinutesStep
        for (preset in presets) {
            val card = column(10).apply {
                val padding = dp(34)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                isFocusable = true
                addPressMotion(0.97f)
                setOnClickListener { selectPreset(preset.id) }
            }
            val header = row(16)
            header.addView(iconTile(presetIcon(preset.id)))
            header.addView(shimpyoText(preset.name, ShimpyoText.HEADLINE).weighted(1f))
            val badge = shimpyoText("선택됨", ShimpyoText.LABEL, ShimpyoColors.PERIWINKLE_DEEP).apply {
                background = roundedDrawable(ShimpyoColors.TINT, 999)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            header.addView(badge)
            card.addView(header.fillWidth())

            card.addView(spacer(weight = 1f))
            val minutesRow = row(6, centerVertical = false) // 숫자와 "분"은 글자 밑줄(기준선)에 맞춘다
            val minutes = shimpyoText("", ShimpyoText.DISPLAY)
            minutesRow.addView(minutes)
            minutesRow.addView(shimpyoText("분", ShimpyoText.HEADLINE))
            card.addView(minutesRow)
            card.addView(
                shimpyoText("${presetDescription(preset.id)} · ${preset.rangeText}", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
            )
            card.addView(spacer(weight = 1f))

            val buttons = row(16)
            buttons.addView(circleTextButton("−", "${preset.name} 1분 줄이기").apply {
                repeatWhileHeld { changePresetMinutes(preset.id, -step) }
            })
            buttons.addView(circleTextButton("+", "${preset.name} 1분 늘리기").apply {
                repeatWhileHeld { changePresetMinutes(preset.id, step) }
            })
            card.addView(buttons)

            presetRow.addView(card.weighted(1f, matchHeight = true))
            presetViews[preset.id] = PresetCardViews(card, minutes, badge)
        }
    }

    private fun presetIcon(presetId: String): Int = when (presetId) {
        RestPreset.ID_SHORT -> R.drawable.ic_shimpyo_eye_closed
        RestPreset.ID_LIGHT -> R.drawable.ic_shimpyo_moon
        else -> R.drawable.ic_shimpyo_bed
    }

    private fun updatePresetViews() {
        for (preset in presets) {
            val views = presetViews[preset.id] ?: continue
            val isSelected = preset.id == selectedPresetId
            views.minutes.text = preset.minutes.toString()
            views.badge.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            views.card.background = if (isSelected) {
                pressableDrawable(ShimpyoColors.SURFACE, ShimpyoSize.CARD_RADIUS, ShimpyoColors.PERIWINKLE, 4)
            } else {
                pressableDrawable(ShimpyoColors.SURFACE, ShimpyoSize.CARD_RADIUS)
            }
            views.card.contentDescription = "${preset.name} ${preset.minutes}분" + if (isSelected) ", 선택됨" else ""
        }
        updateStartButton()
    }

    private fun selectPreset(presetId: String) {
        if (selectedPresetId == presetId) return
        selectedPresetId = presetId
        updatePresetViews()
        lifecycleScope.launch { container.presetRepository.saveSelectedPresetId(presetId) }
    }

    /** 1분씩 바꾼다. 프리셋 범위를 넘지 않는다 (짧은 휴식이 가벼운 낮잠보다 길어지지 않게). */
    private fun changePresetMinutes(presetId: String, deltaMinutes: Int) {
        val preset = presets.firstOrNull { it.id == presetId } ?: return
        val changed = preset.withMinutes(preset.minutes + deltaMinutes)
        selectedPresetId = presetId
        presets = presets.map { if (it.id == presetId) changed else it }
        updatePresetViews()
        lifecycleScope.launch {
            container.presetRepository.savePresetMinutes(changed.id, changed.minutes)
            container.presetRepository.saveSelectedPresetId(presetId)
        }
    }

    private fun updateStartButton() {
        val minutes = presets.firstOrNull { it.id == selectedPresetId }?.minutes ?: return
        startTitle.text = "${minutes}분 쉬기"
        startSub.text = "${formatClock(System.currentTimeMillis() + minutes * SafetyRules.MINUTE)}에 깨워 드릴게요"
        startButton.contentDescription = "${minutes}분 쉬기"
    }

    private fun goToStartConfirm() {
        draft.isTestRun = false
        startActivity(Intent(this, StartConfirmActivity::class.java))
    }

    // ------------------------------------------------------------------
    // 쉬는 동안 차가 해 줄 일
    // ------------------------------------------------------------------

    private suspend fun refreshComfortItems() {
        supportedItems = if (container.appSettings.isComfortControlEnabled) {
            container.restEnvironment.findSupportedItems()
        } else {
            emptySet()
        }
        showComfortPills()
    }

    private fun showComfortPills() {
        comfortPills.removeAllViews()
        if (!container.appSettings.isComfortControlEnabled) {
            comfortPills.addView(shimpyoText("설정에서 꺼 두었어요. 차는 그대로 두고 쉬어요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
            return
        }
        if (supportedItems.isEmpty()) {
            comfortPills.addView(
                shimpyoText("이 차에서는 조절할 수 있는 게 없어요. 타이머와 대나무숲으로 쉬어요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY),
            )
            return
        }
        val approved = draft.approvedOrAll(supportedItems)
        for (item in ComfortItem.entries) {
            if (item !in supportedItems) continue
            val isOn = item in approved
            val pill = pill(
                text = comfortLabel(item),
                background = if (isOn) ShimpyoColors.NAVY else ShimpyoColors.CHIP,
                textColor = if (isOn) Color.WHITE else ShimpyoColors.TEXT,
                iconRes = if (isOn) R.drawable.ic_shimpyo_check else null,
            ) { toggleComfortItem(item) }
            pill.contentDescription = "${comfortLabel(item)}, ${if (isOn) "켜짐" else "꺼짐"}"
            comfortPills.addView(pill)
        }
    }

    private fun toggleComfortItem(item: ComfortItem) {
        val current = draft.approvedOrAll(supportedItems)
        draft.approvedItems = if (item in current) current - item else current + item
        showComfortPills()
    }

    // ------------------------------------------------------------------
    // 대나무숲 · 차 상태 · 기록
    // ------------------------------------------------------------------

    private fun refreshBambooCard() {
        if (draft.hasBambooText) {
            bambooTitle.text = "고민을 맡겨 둘게요"
            bambooSub.text = "쉬기 시작하면 암호화해 둬요"
        } else {
            bambooTitle.text = "대나무숲에 고민 맡기기"
            bambooSub.text = "기기에만 암호화해 둬요"
        }
    }

    private suspend fun refreshVehicleStatus() {
        val reading = container.parkingStatusReader.readParkingStatus()
        val signal = container.vehicleSignalReader.readSignal()
        val openStatus = { startActivity(Intent(this, VehicleStatusActivity::class.java)) }

        statusChips.removeAllViews()
        statusChips.addView(pill(parkingLabel(reading.state), dotColor = parkingColor(reading.state), onClick = openStatus))
        signal.batteryPercent?.let { statusChips.addView(pill("배터리 $it%", onClick = openStatus)) }
        signal.allDoorsLocked?.let { statusChips.addView(pill(if (it) "문 잠김" else "문 열림", onClick = openStatus)) }

        val batteryLow = signal.batteryPercent?.let { it <= container.safetyRules.batteryLowPercent } == true
        headlineSub.text = parkingGuide(reading.state) +
            if (batteryLow) " 배터리가 적으니 실내 온도 맞추기는 끄는 걸 추천해요." else ""
    }

    /** 이번 주(최근 7일) 쉰 횟수와 시간. 개발용 테스트 휴식은 빼고 센다. */
    private suspend fun showWeeklyRecord() {
        val since = System.currentTimeMillis() - WEEK_MILLIS
        val records = container.restHistoryRepository.loadRecords().filter { it.endedAtMillis >= since && !it.isTestRun }
        if (records.isEmpty()) {
            weeklyTitle.text = "이번 주 첫 쉼표"
            weeklySub.text = "잠깐 쉬어 가면 오후가 가벼워져요"
        } else {
            val totalMinutes = records.sumOf { it.actualRestMillis } / SafetyRules.MINUTE
            weeklyTitle.text = "이번 주 ${records.size}번 쉬었어요"
            weeklySub.text = "모두 ${totalMinutes}분"
        }
    }

    // ------------------------------------------------------------------
    // 로딩 화면 (앱을 처음 열 때만)
    // ------------------------------------------------------------------

    private var loadingOverlay: android.widget.FrameLayout? = null
    private var loadingLogo: View? = null
    private var loadingShownAt = 0L
    private val loadingAnimators = mutableListOf<android.animation.Animator>()

    /**
     * 로딩 화면 — "그려지듯 나타나기" (11/11 사용자 선택 C).
     *  1. 남색 몸통이 왼쪽부터 차오른다 (0.9초)
     *  2. 연보라 꼬리가 톡 튀어나와 로고가 완성된다 (0.45초)
     *  3. 화면이 준비되면 로고가 살짝 커지며 사라지고 바탕이 걷힌다 (hideLoadingOverlay)
     * 몸통·꼬리는 같은 좌표의 그림 두 장(ic_shimpyo_logo_body / _tail)을 겹쳐 놓은 것이다.
     * 시스템에서 애니메이션을 끈 경우에는 완성된 로고를 바로 보여준다.
     */
    private fun showLoadingOverlay() {
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(ShimpyoColors.BACKGROUND)
            isClickable = true // 로딩 중에는 아래 화면을 누르지 못하게
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "쉼표를 준비하고 있어요"
        }
        val width = dp(LOADING_LOGO_WIDTH_DP)
        val height = width * 48 / 84 // 로고 그림 비율
        val logo = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        // 꼬리를 먼저 넣어 몸통 아래에 깔리게 한다 (원래 로고와 같은 겹침 순서)
        val tail = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_shimpyo_logo_tail)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
            pivotX = width * TAIL_PIVOT_X
            pivotY = height * TAIL_PIVOT_Y
        }
        val bodyClip = android.graphics.drawable.ClipDrawable(
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_shimpyo_logo_body)!!,
            Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL,
        )
        val body = android.widget.ImageView(this).apply {
            setImageDrawable(bodyClip)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
        }
        if (ShimpyoTheme.isDark) {
            // 어두운 바탕에서는 밤 로고 색(크림 몸통 + 밝은 꼬리)으로
            tail.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8FA7E6"))
            body.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FBF5EA"))
        }
        logo.addView(tail)
        logo.addView(body)
        overlay.addView(logo)
        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadingOverlay = overlay
        loadingLogo = logo
        loadingShownAt = android.os.SystemClock.elapsedRealtime()

        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            bodyClip.level = FULL_LEVEL
            return
        }
        // 1. 몸통이 왼쪽부터 차오른다
        bodyClip.level = 0
        val fill = android.animation.ValueAnimator.ofInt(0, FULL_LEVEL).apply {
            duration = BODY_FILL_MILLIS
            startDelay = 150L
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { bodyClip.level = it.animatedValue as Int }
        }
        loadingAnimators += fill
        fill.start()
        // 2. 꼬리가 톡 튀어나온다
        tail.alpha = 0f
        tail.scaleX = 0f
        tail.scaleY = 0f
        tail.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(150L + BODY_FILL_MILLIS - 120L)
            .setDuration(TAIL_POP_MILLIS)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f))
            .start()
    }

    /** 최소 시간은 보여준 뒤, 로고가 살짝 커지며 옅어지고 바탕이 걷힌다 */
    private suspend fun hideLoadingOverlay() {
        val overlay = loadingOverlay ?: return
        val logo = loadingLogo
        loadingOverlay = null
        loadingLogo = null
        val shownFor = android.os.SystemClock.elapsedRealtime() - loadingShownAt
        if (shownFor < MIN_LOADING_MILLIS) kotlinx.coroutines.delay(MIN_LOADING_MILLIS - shownFor)
        loadingAnimators.forEach { it.end() } // 아직 차오르는 중이면 완성된 모습으로
        loadingAnimators.clear()
        logo?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.alpha(0f)?.setDuration(LOADING_FADE_MILLIS)?.start()
        overlay.animate().alpha(0f).setStartDelay(LOADING_FADE_MILLIS / 2).setDuration(LOADING_FADE_MILLIS).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }.start()
    }

    /** 지난번에 앱이 휴식 도중 꺼졌으면 한 번 알려준다 */
    private fun showInterruptedNotice() {
        val record = container.napController.interruptedNotice.value ?: return
        container.napController.consumeInterruptedNotice()
        android.widget.Toast.makeText(
            this,
            "지난 휴식(${record.presetName})은 앱이 꺼져서 정리해 뒀어요. 차는 원래대로 되돌렸어요.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequest.launch(permission)
        }
    }

    private companion object {
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MIN_LOADING_MILLIS = 1_800L
        const val LOADING_LOGO_WIDTH_DP = 280
        const val LOADING_FADE_MILLIS = 450L
        const val BODY_FILL_MILLIS = 900L
        const val TAIL_POP_MILLIS = 450L
        const val FULL_LEVEL = 10_000 // ClipDrawable 에서 "다 보임"
        // 꼬리가 튀어나오는 기준점 (로고 그림에서 꼬리 가운데: 가로 78%, 세로 26%)
        const val TAIL_PIVOT_X = 0.78f
        const val TAIL_PIVOT_Y = 0.26f
    }
}
