package com.example.hyundaitest.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.R
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.data.model.BambooNote
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 대나무숲 기록 — 보관한 이야기 (3단계 UI, 설계서 화면 8).
 *
 * 내용은 "보기"를 누른 이야기만 보여준다(동승자에게 바로 보이지 않게).
 */
class BambooRecordActivity : AppCompatActivity() {

    private val container by lazy { appContainer }
    private lateinit var list: LinearLayout
    private val revealedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = shimpyoScreen()
        screen.addView(backBar())
        screen.addView(titleBlock("보관한 이야기", "이 기기에만 암호화돼 있어요. '보기'를 누르기 전에는 내용이 보이지 않아요."))
        list = column(20)
        screen.addView(list.fillWidth())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val notes = container.bambooRepository.loadKeptNotes()
            list.removeAllViews()
            if (notes.isEmpty()) {
                val empty = card(gapDp = 12)
                empty.addView(shimpyoText("보관한 이야기가 없어요", ShimpyoText.HEADLINE))
                empty.addView(
                    shimpyoText("깨어난 뒤 '보관하기'를 고른 이야기만 여기에 모여요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY),
                )
                list.addView(empty.fillWidth())
                return@launch
            }
            for (note in notes) list.addView(noteCard(note).fillWidth())
        }
    }

    private fun noteCard(note: BambooNote): LinearLayout {
        val cardView = card(gapDp = 18)
        val isRevealed = note.id in revealedIds
        val header = row(18)
        header.addView(iconTile(R.drawable.ic_shimpyo_lock))
        header.addView(shimpyoText(DATE_FORMAT.format(Date(note.createdAtMillis)), ShimpyoText.BODY_STRONG).weighted(1f))
        header.addView(shimpyoButton(if (isRevealed) "숨기기" else "보기", ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
            if (!revealedIds.add(note.id)) revealedIds.remove(note.id)
            reload()
        })
        header.addView(shimpyoButton("지우기", ButtonKind.GHOST, ShimpyoSize.BUTTON_HEIGHT_SMALL) { confirmDelete(note.id) })
        cardView.addView(header.fillWidth())
        if (isRevealed) {
            cardView.addView(shimpyoText(note.text, ShimpyoText.BODY).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, container.appSettings.memoTextSize.sp)
                background = roundedDrawable(ShimpyoColors.BACKGROUND, 20)
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }.fillWidth())
        }
        return cardView
    }

    private fun confirmDelete(noteId: String) {
        showShimpyoDialog(
            title = "이 이야기를 지울까요?",
            message = "지우면 되돌릴 수 없어요.",
            confirmText = "지우기",
            dismissText = "취소",
            danger = true,
        ) {
            lifecycleScope.launch {
                container.bambooRepository.deleteNote(noteId)
                revealedIds.remove(noteId)
                reload()
            }
        }
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA)
    }
}
