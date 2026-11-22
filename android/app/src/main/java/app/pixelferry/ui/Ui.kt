package app.pixelferry.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny view helpers so the screens stay readable without XML. */
fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.vbox(pad: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Activity.card(): LinearLayout = vbox(14).apply {
    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x22000000) }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(12) }
}

fun Activity.h(text: String): TextView = TextView(this).apply {
    this.text = text; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF202124.toInt()); setPadding(0, 0, 0, dp(6))
}

fun Activity.p(text: String = "", size: Float = 15f, color: Int = 0xFF3C4043.toInt()): TextView = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(2), 0, dp(2))
}

fun Activity.btn(text: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 16f
    if (primary) {
        background = GradientDrawable().apply { setColor(0xFF1A73E8.toInt()); cornerRadius = dp(24).toFloat() }
        setTextColor(Color.WHITE)
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    setOnClickListener { onClick() }
}

fun LinearLayout.add(vararg v: View): LinearLayout { v.forEach { addView(it) }; return this }
