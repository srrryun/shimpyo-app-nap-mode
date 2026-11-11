package com.example.hyundaitest.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup

/**
 * 쉼표 디자인의 확인 창 (기본 안드로이드 확인 창 대신).
 * 둥근 큰 카드, 큰 제목, 큰 버튼 두 개 — 차 화면에서 글자가 잘 보이고 누르기 쉽게.
 *
 * @param confirmText 오른쪽 주 버튼 (남색, danger=true 면 빨강)
 * @param dismissText 왼쪽 버튼 (없으면 버튼 하나)
 * @param cancelable 바깥을 눌러 닫을 수 있는지. 닫히면 onDismiss 가 불린다.
 */
fun Activity.showShimpyoDialog(
    title: String,
    message: String? = null,
    confirmText: String,
    dismissText: String? = null,
    danger: Boolean = false,
    cancelable: Boolean = true,
    onDismiss: () -> Unit = {},
    onConfirm: () -> Unit,
): Dialog {
    val dialog = Dialog(this)
    val box = column(32).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = roundedDrawable(ShimpyoColors.SURFACE, DIALOG_RADIUS_DP)
        setPadding(dp(56), dp(56), dp(56), dp(44))
    }
    box.addView(
        shimpyoText(title, ShimpyoText.HEADLINE).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = ShimpyoFonts.get(this@showShimpyoDialog, FontWeight.EXTRA_BOLD)
            gravity = Gravity.CENTER
        }.fillWidth(),
    )
    if (message != null) {
        box.addView(shimpyoText(message, ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY).apply { gravity = Gravity.CENTER }.fillWidth())
    }
    val buttons = row(20)
    if (dismissText != null) {
        buttons.addView(
            shimpyoButton(dismissText, ButtonKind.SECONDARY, DIALOG_BUTTON_HEIGHT_DP) {
                dialog.dismiss()
                onDismiss()
            }.weightedFixedHeight(1f, DIALOG_BUTTON_HEIGHT_DP),
        )
    }
    buttons.addView(
        shimpyoButton(confirmText, ButtonKind.PRIMARY, DIALOG_BUTTON_HEIGHT_DP) {
            dialog.dismiss()
            onConfirm()
        }.apply {
            if (danger) background = pressableDrawable(ShimpyoColors.DANGER, ShimpyoSize.BUTTON_RADIUS)
        }.weightedFixedHeight(1f, DIALOG_BUTTON_HEIGHT_DP),
    )
    box.addView(buttons.fillWidth())

    dialog.setContentView(box)
    dialog.setCancelable(cancelable)
    dialog.setOnCancelListener { onDismiss() }
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = minOf(dp(DIALOG_WIDTH_DP), resources.displayMetrics.widthPixels - dp(64))
        setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.5f)
    }
    dialog.show()
    return dialog
}

private const val DIALOG_RADIUS_DP = 44
private const val DIALOG_WIDTH_DP = 820
private const val DIALOG_BUTTON_HEIGHT_DP = 112
