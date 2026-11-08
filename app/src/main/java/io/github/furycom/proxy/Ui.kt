package io.github.furycom.proxy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

object Theme {
    const val BG = 0xFF05070B.toInt()
    const val CARD = 0x08FFFFFF
    const val EDGE = 0xFF172230.toInt()
    const val TEXT = 0xFFE8F0F6.toInt()
    const val MUTED = 0xFF7F95A8.toInt()
    const val DIM = 0xFF5D7080.toInt()
    const val WARN = 0xFFFFB020.toInt()
}

/** La couleur néon du moment (suit le mode de chauffe) et tout ce qui doit la suivre. */
object Accent {
    var color: Int = Pal.DEF[1]
    val buttons = ArrayList<Button>()
    val titles = ArrayList<TextView>()
    val seeks = ArrayList<SeekBar>()
    val switches = ArrayList<Switch>()

    fun apply(c: Int) {
        color = c
        for (b in buttons) {
            b.background = neonShape(b.context, c)
            b.setTextColor(c)
        }
        for (t in titles) t.setTextColor(c)
        val cl = ColorStateList.valueOf(c)
        for (s in seeks) { s.progressTintList = cl; s.thumbTintList = cl }
        val tr = ColorStateList.valueOf(Pal.a(c, .35f))
        for (sw in switches) { sw.thumbTintList = cl; sw.trackTintList = tr }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(color: Int, radiusDp: Int = 4, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
    return g
}

/** Carte en verre : fond très léger et filet sombre. */
fun Context.glass(radiusDp: Int = 4): GradientDrawable = shape(Theme.CARD, radiusDp, Theme.EDGE, 1)

fun neonShape(ctx: Context, c: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(Pal.a(c, .10f))
    g.cornerRadius = ctx.dp(4).toFloat()
    g.setStroke((ctx.resources.displayMetrics.density * 1.6f + .5f).toInt(), c)
    return g
}

fun Context.text(s: String, sizeSp: Float = 15f, color: Int = Theme.TEXT, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = sizeSp
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

fun Context.neonButton(label: String, sizeSp: Float = 16f, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = label
    b.textSize = sizeSp
    b.isAllCaps = false
    b.stateListAnimator = null
    b.background = neonShape(this, Accent.color)
    b.setTextColor(Accent.color)
    b.setPadding(dp(14), dp(12), dp(14), dp(12))
    b.setOnClickListener { onClick() }
    Accent.buttons.add(b)
    return b
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
       l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(l, t, r, b)
    return p
}
