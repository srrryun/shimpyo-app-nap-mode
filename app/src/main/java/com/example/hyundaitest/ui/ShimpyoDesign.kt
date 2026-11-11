package com.example.hyundaitest.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.hyundaitest.R

/*
 * 쉼표 디자인 규칙 (3단계 UI, 2026-11-10).
 *
 * 모든 화면은 여기 있는 색·글씨·카드·버튼만 써서 만든다. 느낌을 바꾸고 싶으면 이 파일만 고치면
 * 9개 화면이 한 번에 바뀐다.
 *
 * 방향: 로고 A 색(남색·연보라 파랑·크림), 큰 제목 한 문장 + 둥근 흰 카드 + 큰 버튼 하나.
 * 차 화면(가로, 손이 흔들림)에 맞게 글씨·버튼을 휴대폰보다 크게 둔다.
 * 글꼴: Pretendard (SIL Open Font License 1.1, assets/licenses/Pretendard-OFL.txt)
 */

// ----------------------------------------------------------------------
// 색
// ----------------------------------------------------------------------

/**
 * 지금 화면이 어두운 모드인지. 화면이 만들어질 때마다 ShimpyoApplication 이 기기·설정 값으로 맞춘다.
 * ShimpyoColors 의 색은 이 값에 따라 밝은 색/어두운 색을 돌려준다.
 */
object ShimpyoTheme {
    @Volatile
    var isDark: Boolean = false
}

/**
 * 색. 각 색은 밝은 모드 값과 어두운 모드 값(다크 모드, 11/11 추가)을 함께 가진다.
 * 어두운 모드: 남색 밤하늘 바탕 + 조금 밝은 남색 카드 + 흰 글씨. 자는 사람 눈이 부시지 않게 순백은 피한다.
 */
object ShimpyoColors {
    /** 밝은 모드 색과 어두운 모드 색 한 쌍 */
    private class Pair2(lightHex: String, darkHex: String) {
        private val light = Color.parseColor(lightHex)
        private val dark = Color.parseColor(darkHex)
        fun get() = if (ShimpyoTheme.isDark) dark else light
    }

    private val navy = Pair2("#0E1A4A", "#4A66C4")
    private val periwinkle = Pair2("#6D8BD6", "#8FA7E6")
    private val periwinkleDeep = Pair2("#2F4A9A", "#A9BDF2")
    private val tint = Pair2("#E9EEFB", "#1E2A5E")
    private val cream = Pair2("#FBF5EA", "#141B40")
    private val background = Pair2("#F6F3EE", "#0A0F2C")
    private val surface = Pair2("#FFFFFF", "#161D45")
    private val chip = Pair2("#F1EFEA", "#232C5C")
    private val text = Pair2("#0E1A4A", "#F3F5FC")
    private val textSecondary = Pair2("#353D5C", "#CDD5F0")
    private val textTertiary = Pair2("#414A6B", "#AAB4D8")
    private val ok = Pair2("#2E7D32", "#7FD08A")
    private val warn = Pair2("#9A5B00", "#F2B661")
    private val danger = Pair2("#C62828", "#E5534B")
    private val dangerBackground = Pair2("#FBEDEB", "#2A1020")
    private val dangerTint = Pair2("#F6D9D5", "#4A1C2A")
    private val ripple = Pair2("#220E1A4A", "#33FFFFFF")

    /** 주 버튼·강조 바탕색 (어두운 모드에서는 바탕에 묻히지 않게 조금 밝은 남색). 글씨색으로는 TEXT 를 쓴다. */
    val NAVY: Int get() = navy.get()
    val PERIWINKLE: Int get() = periwinkle.get()
    /** 연한 배경 위 강조 글씨 (연보라 파랑은 글씨로 쓰기엔 대비가 약해서 더 진하게) */
    val PERIWINKLE_DEEP: Int get() = periwinkleDeep.get()
    val TINT: Int get() = tint.get()
    val CREAM: Int get() = cream.get()
    val BACKGROUND: Int get() = background.get()
    val SURFACE: Int get() = surface.get()
    val CHIP: Int get() = chip.get()
    val TEXT: Int get() = text.get()
    /** 설명 글씨. 차 화면에서 흐려 보이지 않게 진하게 둔다 (11/11 사용자 의견) */
    val TEXT_SECONDARY: Int get() = textSecondary.get()
    /** 보조 글씨 */
    val TEXT_TERTIARY: Int get() = textTertiary.get()
    val OK: Int get() = ok.get()
    val WARN: Int get() = warn.get()
    val DANGER: Int get() = danger.get()
    /** 휴식을 멈춘 화면의 연한 빨간 바탕 */
    val DANGER_BACKGROUND: Int get() = dangerBackground.get()
    val DANGER_TINT: Int get() = dangerTint.get()
    val RIPPLE: Int get() = ripple.get()

    // 휴식 중(어두운 화면) — 모드와 상관없이 항상 어둡다
    val NIGHT: Int = Color.parseColor("#0B1238")
    val NIGHT_SURFACE: Int = Color.parseColor("#18214A")
    val NIGHT_TEXT: Int = Color.WHITE
    val NIGHT_TEXT_SECONDARY: Int = Color.parseColor("#C9D3F2")
    val NIGHT_OK: Int = Color.parseColor("#7FD08A")
    val NIGHT_RIPPLE: Int = Color.parseColor("#33FFFFFF")
    /** 슬립 모드 막: 검정 80% — 아래 휴식 화면(남은 시간·로고)이 희미하게 비친다 */
    val SLEEP_DIM: Int = Color.parseColor("#CC000000")
}

// ----------------------------------------------------------------------
// 글씨
// ----------------------------------------------------------------------

enum class FontWeight { REGULAR, MEDIUM, BOLD, EXTRA_BOLD }

/** 글씨 종류. 크기는 sp, 차 화면 기준 */
enum class ShimpyoText(val sizeSp: Float, val weight: FontWeight, val letterSpacing: Float = 0f) {
    TIMER(180f, FontWeight.EXTRA_BOLD, -0.04f),
    DISPLAY(92f, FontWeight.EXTRA_BOLD, -0.03f),
    TITLE(52f, FontWeight.EXTRA_BOLD, -0.02f),
    HEADLINE(32f, FontWeight.BOLD, -0.01f),
    BODY(25f, FontWeight.REGULAR),
    BODY_STRONG(25f, FontWeight.BOLD),
    LABEL(22f, FontWeight.MEDIUM),
    CAPTION(21f, FontWeight.REGULAR),
}

object ShimpyoFonts {
    private val cache = HashMap<FontWeight, Typeface>()

    /** 글꼴 파일을 못 읽으면 기본 글꼴로 (앱이 멈추지 않게) */
    fun get(context: Context, weight: FontWeight): Typeface = cache.getOrPut(weight) {
        val resId = when (weight) {
            FontWeight.REGULAR -> R.font.pretendard_regular
            FontWeight.MEDIUM -> R.font.pretendard_medium
            FontWeight.BOLD -> R.font.pretendard_bold
            FontWeight.EXTRA_BOLD -> R.font.pretendard_extrabold
        }
        val loaded = try {
            ResourcesCompat.getFont(context.applicationContext, resId)
        } catch (e: Exception) {
            null
        }
        loaded ?: if (weight >= FontWeight.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}

// ----------------------------------------------------------------------
// 크기
// ----------------------------------------------------------------------

object ShimpyoSize {
    const val SCREEN_PADDING_H = 56
    const val SCREEN_PADDING_V = 40
    const val CARD_RADIUS = 36
    const val CARD_PADDING = 36
    const val BUTTON_RADIUS = 30
    const val BUTTON_HEIGHT = 104
    const val BUTTON_HEIGHT_SMALL = 72
    const val ICON_BUTTON = 72
}

// ----------------------------------------------------------------------
// 기본 부품
// ----------------------------------------------------------------------

fun Context.shimpyoText(
    text: CharSequence,
    style: ShimpyoText = ShimpyoText.BODY,
    color: Int = ShimpyoColors.TEXT,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    typeface = ShimpyoFonts.get(this@shimpyoText, style.weight)
    setTextColor(color)
    includeFontPadding = false
    setLineSpacing(0f, 1.25f)
    if (style.letterSpacing != 0f) letterSpacing = style.letterSpacing
    // 한국어는 기본적으로 글자 단위로 줄을 바꿔서 "요." 처럼 한 글자만 다음 줄로 떨어질 수 있다.
    // Android 13+ 에서는 어절(띄어쓰기) 단위로 끊는다.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }
}

fun Context.roundedDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

/** 누르면 물결이 퍼지는 둥근 배경 */
fun Context.pressableDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0, night: Boolean = false) =
    RippleDrawable(
        ColorStateList.valueOf(if (night) ShimpyoColors.NIGHT_RIPPLE else ShimpyoColors.RIPPLE),
        roundedDrawable(color, radiusDp, strokeColor, strokeDp),
        null,
    )

/** 세로로 쌓는 상자. gapDp 만큼 자식 사이를 띄운다. */
fun Context.column(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setGap(gapDp)
}

/** 가로로 놓는 상자 */
fun Context.row(gapDp: Int = 0, centerVertical: Boolean = true): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    if (centerVertical) gravity = Gravity.CENTER_VERTICAL
    setGap(gapDp)
}

private fun LinearLayout.setGap(gapDp: Int) {
    if (gapDp <= 0) return
    val size = context.dp(gapDp)
    dividerDrawable = GradientDrawable().apply {
        setSize(size, size)
        setColor(Color.TRANSPARENT)
    }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/**
 * 흰 둥근 카드. 그림자는 쓰지 않는다(차 화면·에뮬레이터에서 모서리가 번져 보여서).
 * 바탕(크림빛 회색)과 흰 카드의 밝기 차이만으로 구분한다. shadow 인자는 예전 코드 호환용.
 */
fun Context.card(
    color: Int = ShimpyoColors.SURFACE,
    paddingDp: Int = ShimpyoSize.CARD_PADDING,
    gapDp: Int = 16,
    radiusDp: Int = ShimpyoSize.CARD_RADIUS,
    shadow: Boolean = true,
): LinearLayout = column(gapDp).apply {
    background = roundedDrawable(color, radiusDp)
    val padding = dp(paddingDp)
    setPadding(padding, padding, padding, padding)
}

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, NIGHT_GHOST }

/** 버튼. PRIMARY 는 한 화면에 하나만 쓴다. */
fun Context.shimpyoButton(
    label: String,
    kind: ButtonKind = ButtonKind.PRIMARY,
    heightDp: Int = ShimpyoSize.BUTTON_HEIGHT,
    onClick: () -> Unit,
): Button = Button(this).apply {
    text = label
    isAllCaps = false
    addPressMotion()
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (heightDp >= ShimpyoSize.BUTTON_HEIGHT) 28f else 22f)
    typeface = ShimpyoFonts.get(this@shimpyoButton, FontWeight.BOLD)
    setPadding(dp(36), 0, dp(36), 0)
    val radius = if (heightDp >= ShimpyoSize.BUTTON_HEIGHT) ShimpyoSize.BUTTON_RADIUS else heightDp / 2
    when (kind) {
        ButtonKind.PRIMARY -> {
            setTextColor(Color.WHITE)
            background = pressableDrawable(ShimpyoColors.NAVY, radius)
        }
        ButtonKind.SECONDARY -> {
            setTextColor(ShimpyoColors.TEXT)
            background = pressableDrawable(ShimpyoColors.TINT, radius)
        }
        ButtonKind.GHOST -> {
            setTextColor(ShimpyoColors.TEXT)
            background = pressableDrawable(ShimpyoColors.SURFACE, radius, ShimpyoColors.CHIP, 2)
        }
        ButtonKind.NIGHT_GHOST -> {
            setTextColor(ShimpyoColors.NIGHT_TEXT)
            background = pressableDrawable(Color.TRANSPARENT, radius, Color.parseColor("#59FFFFFF"), 2, night = true)
        }
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp))
    setOnClickListener { onClick() }
}

/** 동그란 글자 버튼 (−, + 등). 길게 누르기와 함께 쓰려면 onClick 대신 repeatWhileHeld 를 붙인다. */
fun Context.circleTextButton(
    label: String,
    description: String,
    background: Int = ShimpyoColors.CHIP,
    sizeDp: Int = ShimpyoSize.ICON_BUTTON,
): Button = Button(this).apply {
    text = label
    contentDescription = description
    isAllCaps = false
    addPressMotion(0.9f)
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(0, 0, 0, 0)
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
    typeface = ShimpyoFonts.get(this@circleTextButton, FontWeight.BOLD)
    setTextColor(ShimpyoColors.TEXT)
    this.background = pressableDrawable(background, sizeDp / 2)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** 동그란 그림 버튼 (뒤로, 보안 등) */
fun Context.iconButton(
    iconRes: Int,
    description: String,
    background: Int = ShimpyoColors.SURFACE,
    tint: Int = ShimpyoColors.TEXT,
    sizeDp: Int = ShimpyoSize.ICON_BUTTON,
    onClick: () -> Unit,
): ImageButton = ImageButton(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    this.background = pressableDrawable(background, sizeDp / 2)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    addPressMotion(0.9f)
    setOnClickListener { onClick() }
}

/** 둥근 알약 모양 표시. dotColor 를 주면 앞에 작은 점을 찍는다. onClick 을 주면 누를 수 있다. */
fun Context.pill(
    text: String,
    background: Int = ShimpyoColors.SURFACE,
    textColor: Int = ShimpyoColors.TEXT,
    dotColor: Int? = null,
    iconRes: Int? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout = row(10).apply {
    val padH = dp(22)
    val padV = dp(14)
    setPadding(padH, padV, padH, padV)
    if (dotColor != null) {
        addView(View(context).apply {
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        })
    }
    if (iconRes != null) {
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(textColor)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }
    addView(shimpyoText(text, ShimpyoText.LABEL, textColor))
    if (onClick != null) {
        this.background = pressableDrawable(background, 999)
        isClickable = true
        isFocusable = true
        contentDescription = text
        addPressMotion(0.94f)
        setOnClickListener { onClick() }
    } else {
        this.background = roundedDrawable(background, 999)
    }
}

/** 쉼표 로고 (배경 없는 눕힌 쉼표). night = 어두운 화면용 색 */
fun Context.logoView(widthDp: Int, night: Boolean = false): ImageView = ImageView(this).apply {
    setImageResource(if (night || ShimpyoTheme.isDark) R.drawable.ic_shimpyo_logo_night else R.drawable.ic_shimpyo_logo)
    scaleType = ImageView.ScaleType.FIT_CENTER
    adjustViewBounds = true
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** 왼쪽 이름 · 오른쪽 값 한 줄 (요약 카드용) */
fun Context.infoRow(label: String, value: String, valueColor: Int = ShimpyoColors.TEXT, night: Boolean = false): LinearLayout =
    row(24).apply {
        addView(
            shimpyoText(label, ShimpyoText.BODY, if (night) ShimpyoColors.NIGHT_TEXT_SECONDARY else ShimpyoColors.TEXT_SECONDARY)
                .weighted(1f),
        )
        addView(shimpyoText(value, ShimpyoText.BODY_STRONG, valueColor).apply { gravity = Gravity.END })
    }

/** 빈 공간. weight 를 주면 남는 공간을 채운다 */
fun Context.spacer(heightDp: Int = 0, weight: Float = 0f): View = View(this).apply {
    layoutParams = if (weight > 0f) {
        LinearLayout.LayoutParams(1, 0, weight)
    } else {
        LinearLayout.LayoutParams(1, dp(heightDp))
    }
}

// ----------------------------------------------------------------------
// 배치 도우미
// ----------------------------------------------------------------------

/** 가로 상자 안에서 남는 너비를 weight 비율로 나눠 갖는다 */
fun <T : View> T.weighted(weight: Float, matchHeight: Boolean = false): T = apply {
    layoutParams = LinearLayout.LayoutParams(
        0,
        if (matchHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        weight,
    )
}

/** 세로 상자 안에서 너비를 꽉 채운다 */
fun <T : View> T.fillWidth(heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT): T = apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
}

/** 고정 너비 (dp) */
fun <T : View> T.fixedWidth(widthDp: Int, matchHeight: Boolean = false): T = apply {
    layoutParams = LinearLayout.LayoutParams(
        context.dp(widthDp),
        if (matchHeight) ViewGroup.LayoutParams.MATCH_PARENT else (layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT),
    )
}

// ----------------------------------------------------------------------
// 화면 틀
// ----------------------------------------------------------------------

/**
 * 화면의 바탕을 만들고 내용을 쌓을 세로 상자를 돌려준다.
 * 화면이 작아도 잘리지 않게 스크롤되고, 내용이 짧으면 화면을 꽉 채운다(spacer(weight=1f)로 아래 정렬 가능).
 */
fun Activity.shimpyoScreen(background: Int = ShimpyoColors.BACKGROUND, gapDp: Int = 28): LinearLayout {
    val content = column(gapDp).apply {
        setPadding(dp(ShimpyoSize.SCREEN_PADDING_H), dp(ShimpyoSize.SCREEN_PADDING_V), dp(ShimpyoSize.SCREEN_PADDING_H), dp(ShimpyoSize.SCREEN_PADDING_V))
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(background)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    setContentView(scroll)
    return content
}

/** 화면 바탕색 바꾸기 (휴식 중 ↔ 깨어남 처럼 같은 화면에서 밝기가 바뀔 때) */
fun LinearLayout.setScreenBackground(color: Int) {
    (parent as? View)?.setBackgroundColor(color)
}

/** 차 화면에는 뒤로 가기 버튼이 없을 수 있어서, 하위 화면마다 직접 둔다 */
fun Activity.backBar(caption: String? = null): LinearLayout = row(20).apply {
    addView(iconButton(R.drawable.ic_shimpyo_back, "뒤로") { finish() })
    if (caption != null) addView(shimpyoText(caption, ShimpyoText.LABEL, ShimpyoColors.TEXT_SECONDARY))
}

/** 큰 제목 + 설명 한 줄 (토스식 "한 화면에 한 문장"). centered = 가운데 정렬 */
fun Context.titleBlock(
    title: String,
    subtitle: String? = null,
    night: Boolean = false,
    centered: Boolean = false,
    titleColor: Int? = null,
): LinearLayout = column(12).apply {
    if (centered) gravity = Gravity.CENTER_HORIZONTAL
    addView(shimpyoText(title, ShimpyoText.TITLE, titleColor ?: if (night) ShimpyoColors.NIGHT_TEXT else ShimpyoColors.TEXT).apply {
        if (centered) gravity = Gravity.CENTER
    })
    if (subtitle != null) {
        addView(shimpyoText(subtitle, ShimpyoText.BODY, if (night) ShimpyoColors.NIGHT_TEXT_SECONDARY else ShimpyoColors.TEXT_SECONDARY).apply {
            if (centered) gravity = Gravity.CENTER
        })
    }
}

/**
 * 가운데에 놓이는 세로 상자 (너비 maxWidthDp 까지). 중단·완료처럼 내용이 적은 화면을
 * 왼쪽 위에 몰리지 않고 가운데 모아 보여줄 때 쓴다.
 */
fun Context.centeredColumn(maxWidthDp: Int = 1600, gapDp: Int = 28): LinearLayout = column(gapDp).apply {
    gravity = Gravity.CENTER_HORIZONTAL
    val available = resources.displayMetrics.widthPixels - dp(ShimpyoSize.SCREEN_PADDING_H * 2)
    layoutParams = LinearLayout.LayoutParams(minOf(dp(maxWidthDp), available), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
}

/** 연한 바탕의 둥근 사각형 안에 아이콘 (카드 왼쪽 장식) */
fun Context.iconTile(iconRes: Int, background: Int = ShimpyoColors.TINT, tint: Int = ShimpyoColors.PERIWINKLE_DEEP): ImageView =
    ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER
        this.background = roundedDrawable(background, 20)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
    }

/** 누를 수 있는 카드(가로). 카드 전체가 버튼 하나처럼 동작한다. */
fun Context.pressableRowCard(description: String, onClick: () -> Unit): LinearLayout = row(20).apply {
    background = pressableDrawable(ShimpyoColors.SURFACE, ShimpyoSize.CARD_RADIUS)
    val padding = dp(32)
    setPadding(padding, padding, padding, padding)
    isClickable = true
    isFocusable = true
    contentDescription = description
    addPressMotion(0.97f)
    setOnClickListener { onClick() }
}

/** 설정·목록용 한 줄: 제목 + 설명 + 오른쪽 화살표. 줄 전체를 누를 수 있다. */
fun Context.linkRow(title: String, subtitle: String? = null, onClick: () -> Unit): LinearLayout = row(20).apply {
    background = pressableDrawable(ShimpyoColors.SURFACE, 20)
    setPadding(dp(8), dp(18), dp(8), dp(18))
    isClickable = true
    isFocusable = true
    contentDescription = title
    addPressMotion(0.98f)
    setOnClickListener { onClick() }
    val texts = column(6)
    texts.addView(shimpyoText(title, ShimpyoText.BODY_STRONG))
    if (subtitle != null) texts.addView(shimpyoText(subtitle, ShimpyoText.CAPTION, ShimpyoColors.TEXT_TERTIARY))
    addView(texts.weighted(1f))
    addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_shimpyo_chevron)
        imageTintList = ColorStateList.valueOf(ShimpyoColors.TEXT_TERTIARY)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    })
}


/**
 * 누르는 동안 살짝 작아졌다가 떼면 돌아오는 움직임 (모든 버튼·누르는 카드에 공통).
 * StateListAnimator 를 써서 손가락 처리(길게 누르기 등)와 겹치지 않는다.
 */
fun View.addPressMotion(pressedScale: Float = 0.95f) {
    val target = this
    stateListAnimator = android.animation.StateListAnimator().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                target,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale),
            ).setDuration(PRESS_DOWN_MILLIS),
        )
        addState(
            intArrayOf(),
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                target,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
            ).setDuration(PRESS_UP_MILLIS),
        )
    }
}

private const val PRESS_DOWN_MILLIS = 90L
private const val PRESS_UP_MILLIS = 180L

/** 가로 상자 안에서 남는 너비를 나눠 가지면서, 높이는 정해 둔 값으로 (weighted 는 높이를 글자 높이로 줄인다) */
fun <T : View> T.weightedFixedHeight(weight: Float, heightDp: Int): T = apply {
    layoutParams = LinearLayout.LayoutParams(0, context.dp(heightDp), weight)
}

/** 쉬는 동안 화면을 만지지 않으면 슬립 모드로 들어가기까지의 시간(초) */
const val SLEEP_AFTER_SECONDS = 10

/**
 * 글씨 크기 조절 줄: [가−]  크게  [가+]
 * 누를 때마다 onChange 로 새 단계를 알려 준다. 끝 단계면 그쪽 버튼을 흐리게 한다.
 */
fun Context.memoTextSizeControl(
    initial: com.example.hyundaitest.data.local.MemoTextSize,
    onChange: (com.example.hyundaitest.data.local.MemoTextSize) -> Unit,
): LinearLayout = row(14).apply {
    var current = initial
    addView(shimpyoText("글씨 크기", ShimpyoText.BODY_STRONG, ShimpyoColors.TEXT_SECONDARY))
    lateinit var smaller: Button
    lateinit var larger: Button
    val label = shimpyoText(current.label, ShimpyoText.BODY_STRONG).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    fun refresh() {
        label.text = current.label
        smaller.alpha = if (current.isSmallest) 0.35f else 1f
        larger.alpha = if (current.isLargest) 0.35f else 1f
    }
    smaller = shimpyoButton("가 −", ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
        if (!current.isSmallest) { current = current.smaller(); refresh(); onChange(current) }
    }.apply { contentDescription = "글씨 작게" }
    larger = shimpyoButton("가 +", ButtonKind.SECONDARY, ShimpyoSize.BUTTON_HEIGHT_SMALL) {
        if (!current.isLargest) { current = current.larger(); refresh(); onChange(current) }
    }.apply {
        contentDescription = "글씨 크게"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
    }
    addView(smaller)
    addView(label)
    addView(larger)
    refresh()
}

