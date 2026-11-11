package com.example.hyundaitest.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.hyundaitest.VehicleCheckActivity
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.data.local.ThemeMode
import com.example.hyundaitest.data.local.applyToApp

/**
 * 설정 (3단계 UI).
 *  - 쉬는 동안 차가 해 줄 일 켜기/끄기 (설계서 7장 스위치)
 *  - 화면: 다크 모드(기기 따라가기/밝게/어둡게), 슬립 모드 켜기/끄기
 *  - 대나무숲: 보관한 이야기
 *  - 안전·보안: 보안 상태
 *  - 앱 정보: 버전, 글꼴 라이선스
 *  - 개발용(디버그 빌드만): 1분 테스트, 차량 기능 점검
 */
class SettingsActivity : AppCompatActivity() {

    private val container by lazy { appContainer }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = shimpyoScreen()
        screen.addView(backBar())
        screen.addView(titleBlock("설정"))

        val columns = row(28, centerVertical = false).apply { isBaselineAligned = false }
        val left = column(28)
        val right = column(28)

        // 쉬는 동안 차가 해 줄 일
        val comfortCard = card(gapDp = 16)
        val switchRow = row(20)
        val switchTexts = column(6)
        switchTexts.addView(shimpyoText(COMFORT_SECTION_TITLE, ShimpyoText.HEADLINE))
        switchTexts.addView(shimpyoText(COMFORT_SECTION_GUIDE, ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY))
        switchRow.addView(switchTexts.weighted(1f))
        switchRow.addView(SwitchCompat(this).apply {
            isChecked = container.appSettings.isComfortControlEnabled
            contentDescription = COMFORT_SECTION_TITLE
            scaleX = 1.4f
            scaleY = 1.4f
            setOnCheckedChangeListener { _, isOn -> container.appSettings.isComfortControlEnabled = isOn }
        })
        comfortCard.addView(switchRow.fillWidth())
        comfortCard.addView(
            shimpyoText("끄면 차는 그대로 두고 타이머·알람·대나무숲으로만 쉬어요. 항목 하나씩은 홈에서 켜고 끌 수 있어요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY),
        )
        left.addView(comfortCard.fillWidth())
        left.addView(screenCard().fillWidth())

        // 대나무숲
        val bambooCard = card(gapDp = 4)
        bambooCard.addView(shimpyoText("대나무숲", ShimpyoText.HEADLINE))
        bambooCard.addView(linkRow("보관한 이야기", "깨어난 뒤 '보관하기'를 고른 이야기") {
            startActivity(Intent(this, BambooRecordActivity::class.java))
        }.fillWidth())
        bambooCard.addView(
            shimpyoText("고르지 않고 휴식을 마치면 지우는 걸 먼저 권해요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
        )
        left.addView(bambooCard.fillWidth())

        // 안전·보안
        val safetyCard = card(gapDp = 4)
        safetyCard.addView(shimpyoText("안전·보안", ShimpyoText.HEADLINE))
        safetyCard.addView(linkRow("보안 상태", "암호화·인터넷 권한·외부 입구 검사 결과") {
            startActivity(Intent(this, SecurityStatusActivity::class.java))
        }.fillWidth())
        safetyCard.addView(linkRow("지금 차 상태", "주차·배터리·문·공조") {
            startActivity(Intent(this, VehicleStatusActivity::class.java))
        }.fillWidth())
        right.addView(safetyCard.fillWidth())

        // 앱 정보
        val infoCard = card(gapDp = 16)
        infoCard.addView(shimpyoText("앱 정보", ShimpyoText.HEADLINE))
        infoCard.addView(infoRow("버전", appVersionName()).fillWidth())
        infoCard.addView(infoRow("만든 팀", "SOLVIX").fillWidth())
        infoCard.addView(
            shimpyoText("글꼴 Pretendard (SIL Open Font License 1.1)", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
        )
        right.addView(infoCard.fillWidth())

        // 개발용
        if (isDebugBuild()) {
            val devCard = card(gapDp = 4)
            devCard.addView(shimpyoText("개발용", ShimpyoText.HEADLINE))
            devCard.addView(linkRow("1분 테스트로 쉬어 보기", "출시 빌드에는 보이지 않아요") {
                container.restDraft.isTestRun = true
                startActivity(Intent(this, StartConfirmActivity::class.java))
            }.fillWidth())
            devCard.addView(linkRow("차량 기능 점검", "지원 기능 확인·제어 시험") {
                startActivity(Intent(this, VehicleCheckActivity::class.java))
            }.fillWidth())
            right.addView(devCard.fillWidth())
        }

        columns.addView(left.weighted(1f))
        columns.addView(right.weighted(1f))
        screen.addView(columns.fillWidth())
    }

    /** 화면: 다크 모드 고르기 + 슬립 모드 스위치 */
    private fun screenCard(): android.widget.LinearLayout {
        val card = card(gapDp = 20)
        card.addView(shimpyoText("화면", ShimpyoText.HEADLINE))
        card.addView(shimpyoText("어두운 화면은 쉬기 전후로 눈이 덜 부셔요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY))
        val modeRow = row(12)
        val modeButtons = mutableMapOf<ThemeMode, android.widget.Button>()
        fun paint() {
            val current = container.appSettings.themeMode
            modeButtons.forEach { (mode, button) ->
                val selected = mode == current
                button.background = pressableDrawable(if (selected) ShimpyoColors.NAVY else ShimpyoColors.TINT, ShimpyoSize.BUTTON_HEIGHT_SMALL / 2)
                button.setTextColor(if (selected) android.graphics.Color.WHITE else ShimpyoColors.TEXT)
            }
        }
        for (mode in ThemeMode.entries) {
            val button = shimpyoButton(mode.label, ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
                if (container.appSettings.themeMode == mode) return@shimpyoButton
                container.appSettings.themeMode = mode
                paint()
                mode.applyToApp() // 밝기가 실제로 바뀌면 화면이 저절로 다시 그려진다
            }.weightedFixedHeight(1f, ShimpyoSize.BUTTON_HEIGHT_SMALL)
            button.contentDescription = "화면 모드 ${mode.label}"
            modeButtons[mode] = button
            modeRow.addView(button)
        }
        paint()
        card.addView(modeRow.fillWidth())

        val sleepRow = row(20)
        val sleepTexts = column(6)
        sleepTexts.addView(shimpyoText("슬립 모드", ShimpyoText.BODY_STRONG))
        sleepTexts.addView(
            shimpyoText("쉬는 동안 ${SLEEP_AFTER_SECONDS}초 넘게 화면을 만지지 않으면 화면이 아주 어두워져요. 누르면 켜지고, 알람이 울리면 저절로 켜져요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
        )
        sleepRow.addView(sleepTexts.weighted(1f))
        sleepRow.addView(SwitchCompat(this).apply {
            isChecked = container.appSettings.isSleepModeEnabled
            contentDescription = "슬립 모드"
            scaleX = 1.4f
            scaleY = 1.4f
            setOnCheckedChangeListener { _, isOn -> container.appSettings.isSleepModeEnabled = isOn }
        })
        card.addView(sleepRow.fillWidth())
        return card
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (e: Exception) {
        "-"
    }
}
