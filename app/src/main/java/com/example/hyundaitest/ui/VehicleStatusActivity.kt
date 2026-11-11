package com.example.hyundaitest.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.safety.ParkingReading
import com.example.hyundaitest.vehicle.ComfortItem
import com.example.hyundaitest.vehicle.VehicleSignal
import kotlinx.coroutines.launch

/**
 * 쉼표 시그널 — 지금 차 상태 (3단계 UI, 설계서 화면 2·기능 1).
 * 확인한 값만 보여주고, 읽지 못한 값은 "확인 불가"로 따로 표시한다(정상이라고 표시하지 않는다).
 */
class VehicleStatusActivity : AppCompatActivity() {

    private val container by lazy { appContainer }
    private lateinit var statusCard: LinearLayout
    private lateinit var comfortCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = shimpyoScreen()
        screen.addView(backBar())
        screen.addView(titleBlock("지금 차 상태예요", "확인하지 못한 항목은 '확인 불가'로 보여드려요."))

        val cards = row(24, centerVertical = false).apply { isBaselineAligned = false }
        statusCard = card(gapDp = 22)
        comfortCard = card(gapDp = 22)
        cards.addView(statusCard.weighted(1.3f, matchHeight = true))
        cards.addView(comfortCard.weighted(1f, matchHeight = true))
        screen.addView(cards.fillWidth())

        screen.addView(shimpyoButton("다시 확인하기", ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) { refresh() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        statusCard.removeAllViews()
        statusCard.addView(shimpyoText("확인하는 중…", ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY))
        lifecycleScope.launch {
            val reading = container.parkingStatusReader.readParkingStatus()
            val signal = container.vehicleSignalReader.readSignal()
            val supported = container.restEnvironment.findSupportedItems()
            showStatus(reading, signal)
            showComfort(supported)
        }
    }

    private fun showStatus(reading: ParkingReading, signal: VehicleSignal) {
        statusCard.removeAllViews()
        statusCard.addView(shimpyoText("차 상태", ShimpyoText.HEADLINE))
        statusCard.addView(infoRow("주차", parkingLabel(reading.state), parkingColor(reading.state)).fillWidth())
        statusCard.addView(infoRow("파킹브레이크", onOff(reading.isParkingBrakeOn, "걸림", "풀림")).fillWidth())
        statusCard.addView(infoRow("기어", onOff(reading.isGearInPark, "P단", "P단 아님")).fillWidth())
        if (!signal.isVehicleConnected) {
            statusCard.addView(shimpyoText("차량에 연결되지 않아 나머지는 확인할 수 없어요.", ShimpyoText.CAPTION, ShimpyoColors.WARN))
            return
        }
        statusCard.addView(infoRow("배터리", signal.batteryPercent?.let { "$it%" } ?: UNKNOWN).fillWidth())
        statusCard.addView(infoRow("문 잠금", onOff(signal.allDoorsLocked, "잠김", "열림")).fillWidth())
        val climate = when (signal.hvacPowerOn) {
            true -> "켜짐" + (signal.hvacTemperatureCelsius?.let { " · ${"%.1f".format(it)}℃" } ?: "")
            false -> "꺼짐"
            null -> UNKNOWN
        }
        statusCard.addView(infoRow("공조", climate).fillWidth())
    }

    private fun showComfort(supported: Set<ComfortItem>) {
        comfortCard.removeAllViews()
        comfortCard.addView(shimpyoText("쉬는 동안 맞춰 줄 수 있는 것", ShimpyoText.HEADLINE))
        for (item in ComfortItem.entries) {
            val isSupported = item in supported
            comfortCard.addView(
                infoRow(item.label, if (isSupported) "가능" else "지원 안 함", if (isSupported) ShimpyoColors.OK else ShimpyoColors.TEXT_TERTIARY)
                    .fillWidth(),
            )
        }
        comfortCard.addView(
            shimpyoText("차가 맞춰 준 뒤에는 깨어날 때 원래대로 되돌려요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
        )
    }

    private fun onOff(value: Boolean?, whenTrue: String, whenFalse: String): String = when (value) {
        true -> whenTrue
        false -> whenFalse
        null -> UNKNOWN
    }

    private companion object {
        const val UNKNOWN = "확인 불가"
    }
}
