package app.pixelferry.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PixelFerry design system. Colours are checked against WCAG 2.1 AA
 * (text >= 4.5:1, large text/icons >= 3:1) by WcagTest.
 */
object C {
    const val BG = 0xFFF3F5FB.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val INK = 0xFF111827.toInt()        // 17.7:1 on white
    const val INK2 = 0xFF4B5563.toInt()       // 7.6:1 on white
    const val PRIMARY = 0xFF2F49D1.toInt()    // 6.6:1 on white
    const val PRIMARY2 = 0xFF6D28D9.toInt()   // violet end of the gradient, 7.1:1
    const val SUCCESS = 0xFF047857.toInt()    // 5.5:1
    const val WARN = 0xFF92400E.toInt()       // 7.1:1
    const val DANGER = 0xFFB91C1C.toInt()     // 6.5:1
    const val TINT_BLUE = 0xFFE8EDFF.toInt()
    const val TINT_GREEN = 0xFFE3F7EE.toInt()
    const val TINT_AMBER = 0xFFFFF4DC.toInt()
    const val TINT_VIOLET = 0xFFF1E9FF.toInt()
    const val TINT_RED = 0xFFFDE8E8.toInt()
    const val LINE = 0xFFE5E7EB.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun gradient(vararg colors: Int, radius: Float = 0f) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply { setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor) }

fun ripple(content: android.graphics.drawable.Drawable, mask: android.graphics.drawable.Drawable? = null) =
    RippleDrawable(ColorStateList.valueOf(0x22000000), content, mask)

fun Activity.vbox(pad: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun Activity.card(tint: Int = C.CARD): LinearLayout = vbox(18).apply {
    background = rounded(tint, dpf(22f))
    elevation = dpf(2f)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(dp(16), 0, dp(16), dp(14)) }
}

fun Context.text(s: CharSequence, size: Float = 16f, color: Int = C.INK2, bold: Boolean = false): TextView = TextView(this).apply {
    text = s; textSize = size; setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    setLineSpacing(0f, 1.15f)
}

fun Activity.h(text: String, size: Float = 21f): TextView = text(text, size, C.INK, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
fun Activity.p(text: String = "", size: Float = 16f, color: Int = C.INK2): TextView = text(text, size, color).apply { setPadding(0, dp(2), 0, dp(2)) }

/** Section label above a group of tiles. */
fun Activity.section(text: String): TextView = text(text.uppercase(), 13f, C.INK2, bold = true).apply {
    letterSpacing = 0.08f
    setPadding(dp(24), dp(10), dp(24), dp(8))
}

/** Big, friendly primary button (56dp, gradient pill). */
fun Activity.btn(text: String, primary: Boolean = false, color: Int = C.PRIMARY, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 17f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    stateListAnimator = null
    minHeight = dp(56)
    if (primary) {
        val bg = if (color == C.PRIMARY) gradient(C.PRIMARY, C.PRIMARY2, radius = dpf(28f)) else rounded(color, dpf(28f))
        background = ripple(bg); setTextColor(Color.WHITE); elevation = dpf(3f)
    } else {
        background = ripple(rounded(Color.WHITE, dpf(28f), dp(2), color)); setTextColor(color)
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) }
    setOnClickListener { onClick() }
}

/** Quiet text button. */
fun Activity.link(text: String, color: Int = C.PRIMARY, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 16f; setTextColor(color); stateListAnimator = null
    background = ripple(rounded(Color.TRANSPARENT, dpf(24f)), rounded(Color.BLACK, dpf(24f)))
    minHeight = dp(48)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) }
    setOnClickListener { onClick() }
}

/** Draws an emoji as a picture (decorative icon, not text: screen readers skip it). */
class EmojiDrawable(private val emoji: String, private val px: Float) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px; textAlign = Paint.Align.CENTER }
    override fun draw(c: Canvas) {
        val b = bounds; val fm = paint.fontMetrics
        c.drawText(emoji, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Decorative emoji icon. Drawn as the view's background (not image content), which is how
 * Android marks a graphic as decorative: it always sits next to a text label that carries
 * the meaning, so screen readers skip it (WCAG 1.4.11 exempts decorative graphics).
 */
fun Context.emoji(symbol: String, sizeSp: Float): View = View(this).apply {
    val px = sizeSp * resources.displayMetrics.scaledDensity
    background = EmojiDrawable(symbol, px)
    layoutParams = LinearLayout.LayoutParams((px * 1.3f).toInt(), (px * 1.3f).toInt())
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Round icon badge with a decorative emoji. */
fun Context.badge(symbol: String, tint: Int, size: Int = 52): View = View(this).apply {
    background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(tint, dpf(size / 2f)), EmojiDrawable(symbol, dpf(size * 0.46f))))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** A big tappable row: icon, title, subtitle, chevron. 76dp tall: easy to hit. */
fun Activity.tile(symbol: String, tint: Int, title: String, subtitle: String, highlight: Boolean = false, onClick: () -> Unit): LinearLayout =
    hbox().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(76)
        background = ripple(rounded(C.CARD, dpf(22f), if (highlight) dp(2) else 0, C.PRIMARY), rounded(Color.BLACK, dpf(22f)))
        elevation = dpf(2f)
        isClickable = true; isFocusable = true
        contentDescription = "$title. $subtitle"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(16), 0, dp(16), dp(10)) }
        addView(badge(symbol, tint))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
            addView(text(title, 17f, C.INK, bold = true))
            addView(text(subtitle, 14.5f, C.INK2))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 28f, C.INK2))
        setOnClickListener { onClick() }
    }

/** Small rounded status pill. */
fun Context.chip(text: String, fg: Int, bg: Int): TextView = text(text, 13.5f, fg, bold = true).apply {
    background = rounded(bg, dpf(14f)); setPadding(dp(12), dp(5), dp(12), dp(5))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** Gradient hero header shown on top of each screen. */
fun Activity.hero(title: String, subtitle: String, symbol: String? = null): LinearLayout = vbox(0).apply {
    background = gradient(C.PRIMARY, C.PRIMARY2)
    setPadding(dp(24), dp(28), dp(24), dp(30))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(16) }
    val row = hbox()
    if (symbol != null) row.addView(badge(symbol, 0x33FFFFFF, 48).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
    row.addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 26f, Color.WHITE, bold = true).apply { tag = "heroTitle" })
        addView(text(subtitle, 15.5f, 0xFFF1F3FF.toInt()).apply { tag = "heroSub" })
    })
    addView(row)
}

fun LinearLayout.add(vararg v: View): LinearLayout { v.forEach { addView(it) }; return this }

/** Big circular progress ring with the percentage in the middle. */
class ProgressRing(ctx: Context) : View(ctx) {
    var fraction = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var center = "0%"; set(v) { field = v; invalidate() }
    var caption = ""; set(v) { field = v; invalidate() }
    var indeterminate = false; set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.TINT_BLUE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.INK; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.INK2; textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private var spin = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dp(200); setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        val sw = context.dpf(16f)
        val size = minOf(width, height) - sw
        val left = (width - size) / 2f; val top = (height - size) / 2f
        box.set(left, top, left + size, top + size)
        track.strokeWidth = sw; arc.strokeWidth = sw
        arc.shader = android.graphics.SweepGradient(width / 2f, height / 2f, intArrayOf(C.PRIMARY, C.PRIMARY2, C.PRIMARY), null)
        c.drawArc(box, 0f, 360f, false, track)
        if (indeterminate) {
            c.drawArc(box, spin - 90f, 70f, false, arc); spin = (spin + 6f) % 360f; postInvalidateOnAnimation()
        } else if (fraction > 0f) c.drawArc(box, -90f, 360f * fraction, false, arc)
        big.textSize = context.dpf(40f); small.textSize = context.dpf(14f)
        c.drawText(center, width / 2f, height / 2f + context.dpf(10f), big)
        if (caption.isNotEmpty()) c.drawText(caption, width / 2f, height / 2f + context.dpf(34f), small)
        contentDescription = "$center $caption"
    }
}

/** Centre a view horizontally inside a full-width frame. */
fun Context.centered(v: View): FrameLayout = FrameLayout(this).apply {
    addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
}

/** Three stats side by side ("1,234 of 10,000 · 38 MB/s · 12 min left"). */
fun Context.statRow(vararg pairs: Pair<String, String>): LinearLayout = hbox().apply {
    setPadding(0, dp(12), 0, dp(4))
    pairs.forEach { (value, label) ->
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(text(value, 18f, C.INK, bold = true).apply { gravity = Gravity.CENTER; tag = label })
            addView(text(label, 13f, C.INK2).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
