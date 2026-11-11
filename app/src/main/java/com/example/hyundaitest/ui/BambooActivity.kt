package com.example.hyundaitest.ui

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.example.hyundaitest.R
import com.example.hyundaitest.appContainer

/**
 * 대나무숲 — 고민 맡기기 (3단계 UI, 설계서 화면 4).
 *
 * 쓴 글은 아직 저장하지 않고 RestDraft(메모리)에만 둔다. 휴식을 시작하는 순간 암호화 저장소로 옮긴다.
 */
class BambooActivity : AppCompatActivity() {

    private val draft by lazy { appContainer.restDraft }
    private val settings by lazy { appContainer.appSettings }
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = shimpyoScreen()
        screen.addView(backBar())
        screen.addView(titleBlock("머릿속 고민, 잠깐 맡겨 두세요", "깨어나면 다시 돌려드려요. 지우거나 보관하는 건 그때 고를 수 있어요."))

        val inputCard = card(gapDp = 20)
        input = EditText(this).apply {
            setText(draft.bambooText)
            hint = "지금 마음에 걸리는 걸 편하게 적어 보세요"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.memoTextSize.sp)
            typeface = ShimpyoFonts.get(this@BambooActivity, FontWeight.REGULAR)
            setTextColor(ShimpyoColors.TEXT)
            setHintTextColor(ShimpyoColors.TEXT_TERTIARY)
            background = null
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            // 한글 입력: 글자 수 제한(InputFilter)이나 자동 대문자 같은 옵션은 한글 조합(ㄱ+ㅏ→가)을 끊을 수 있어서 쓰지 않는다.
            // 길이는 "맡기기"를 누를 때 자른다.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLineSpacing(0f, 1.3f)
        }
        // 글씨 크기: 고르면 바로 커지고, 다음에도 기억한다 (보관한 이야기·깨어남 화면도 같은 크기)
        inputCard.addView(
            memoTextSizeControl(settings.memoTextSize) { size ->
                settings.memoTextSize = size
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.sp)
            }.fillWidth(),
        )
        inputCard.addView(input.fillWidth())
        val lockLine = row(12)
        lockLine.addView(iconTile(R.drawable.ic_shimpyo_lock))
        lockLine.addView(
            shimpyoText("이 기기에만 암호화해서 저장돼요. 서버로 보내지 않아요.", ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY)
                .weighted(1f),
        )
        inputCard.addView(lockLine.fillWidth())
        screen.addView(inputCard.fillWidth())

        screen.addView(spacer(weight = 1f))
        val buttons = row(20).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(shimpyoButton("비우기", ButtonKind.GHOST) {
            input.setText("")
            draft.bambooText = ""
        })
        buttons.addView(shimpyoButton("맡기기") {
            draft.bambooText = input.text.toString().trim().take(MAX_LENGTH)
            finish()
        }.fixedWidth(320))
        screen.addView(buttons.fillWidth())
    }

    private companion object {
        const val MAX_LENGTH = 2000
    }
}
