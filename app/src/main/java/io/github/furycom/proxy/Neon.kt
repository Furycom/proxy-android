package io.github.furycom.proxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Couleurs du look « néon sobre ». */
object Pal {
    val DEF = intArrayOf(0xFF2D9BFF.toInt(), 0xFF27FFB0.toInt(), 0xFFFF2D55.toInt(), 0xFFF2F6FA.toInt())
    val CYAN = 0xFF6FDCFF.toInt()
    val OFF = 0xFF7F95A8.toInt()
    val WARN = 0xFFFFB020.toInt()
    val RED = 0xFFFF2D55.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val TXT = 0xFFE8F0F6.toInt()
    val BIG = 0xFFEEF4F9.toInt()
    val MUT = 0xFF7F95A8.toInt()
    val DIM = 0xFF5D7080.toInt()
    val SOFT = 0xFF9DB0BF.toInt()
    val BTN = 0xFFB4C4D2.toInt()
    val EDGE = 0xFF172230.toInt()

    fun a(c: Int, f: Float): Int {
        val al = (f.coerceIn(0f, 1f) * 255f + .5f).toInt()
        return (c and 0x00FFFFFF) or (al shl 24)
    }

    fun mul(c: Int, f: Float): Int {
        val al = (((c ushr 24) and 255) * f.coerceIn(0f, 1f) + .5f).toInt()
        return (c and 0x00FFFFFF) or (al shl 24)
    }
}

object Fonts {
    val REG: Typeface = Typeface.DEFAULT
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** Pinceau réutilisable : néon épais (halo en couches), texte, formes. */
class Ink {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val t = Paint(Paint.ANTI_ALIAS_FLAG)
    @PublishedApi internal val ex = floatArrayOf(18f, 12f, 8f, 5f, 2.5f)
    @PublishedApi internal val al = floatArrayOf(.035f, .06f, .10f, .17f, .30f)
    private val gsw = floatArrayOf(9f, 5.5f, 3f)
    private val gsa = floatArrayOf(.07f, .13f, .24f)

    fun fill(color: Int): Paint {
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
        return p
    }

    fun line(color: Int, w: Float): Paint {
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
        p.strokeWidth = w
        return p
    }

    /** Trait néon : halo en couches, coeur coloré, filet blanc. (inline : aucun objet créé à chaque image) */
    inline fun neon(c: Canvas, color: Int, w: Float, k: Float = 1f, draw: (Canvas, Paint) -> Unit) {
        for (i in ex.indices) {
            line(Pal.mul(color, al[i] * k), w + ex[i])
            draw(c, p)
        }
        val kk = min(1f, k)
        line(Pal.mul(color, kk), w)
        draw(c, p)
        line(Pal.a(Pal.WHITE, .55f * kk), max(1f, w * .3f))
        draw(c, p)
    }

    fun glowDot(c: Canvas, x: Float, y: Float, r: Float, color: Int, k: Float = 1f) {
        c.drawCircle(x, y, r + 9f, fill(Pal.mul(color, .05f * k)))
        c.drawCircle(x, y, r + 6f, fill(Pal.mul(color, .10f * k)))
        c.drawCircle(x, y, r + 3.5f, fill(Pal.mul(color, .20f * k)))
        c.drawCircle(x, y, r, fill(Pal.mul(color, k)))
    }

    fun glowPath(c: Canvas, path: Path, color: Int) {
        val sw = gsw
        val sa = gsa
        for (i in sw.indices) c.drawPath(path, line(Pal.mul(color, sa[i]), sw[i]))
        c.drawPath(path, fill(color))
    }

    private fun setT(size: Float, color: Int, align: Paint.Align, tf: Typeface, spacing: Float) {
        t.reset(); t.isAntiAlias = true
        t.typeface = tf
        t.textSize = size
        t.color = color
        t.textAlign = align
        t.letterSpacing = spacing
    }

    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
             align: Paint.Align = Paint.Align.CENTER, tf: Typeface = Fonts.REG, spacing: Float = 0f) {
        setT(size, color, align, tf, spacing)
        c.drawText(s, x, y, t)
    }

    fun glowText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                 align: Paint.Align = Paint.Align.CENTER, tf: Typeface = Fonts.REG, spacing: Float = 0f) {
        val sw = gsw
        val sa = gsa
        for (i in sw.indices) {
            setT(size, Pal.mul(color, sa[i]), align, tf, spacing)
            t.style = Paint.Style.STROKE
            t.strokeWidth = sw[i]
            t.strokeJoin = Paint.Join.ROUND
            c.drawText(s, x, y, t)
        }
        setT(size, color, align, tf, spacing)
        c.drawText(s, x, y, t)
    }

    fun width(s: String, size: Float, tf: Typeface = Fonts.REG, spacing: Float = 0f): Float {
        setT(size, Pal.WHITE, Paint.Align.LEFT, tf, spacing)
        return t.measureText(s)
    }

    /** Lignes fines horizontales (aspect métal brossé) dans un rectangle. */
    fun brushed(c: Canvas, r: RectF) {
        val q = line(0x0BFFFFFF, .6f)
        var y = r.top + 1f
        while (y < r.bottom) {
            c.drawLine(r.left + 1f, y, r.right - 1f, y, q)
            y += 2f
        }
    }

    /** Carte en verre : fond très léger, filet sombre, reflet en haut. */
    fun glass(c: Canvas, r: RectF, radius: Float = 3f) {
        c.drawRoundRect(r, radius, radius, fill(0x08FFFFFF))
        brushed(c, r)
        c.drawRoundRect(r, radius, radius, line(Pal.EDGE, 1f))
        c.drawLine(r.left + 6f, r.top + .5f, r.right - 6f, r.top + .5f, line(0x30FFFFFF, 1f))
    }
}

/** Texte « préfixe + nombre + suffixe » refait seulement quand le nombre change (rien d'alloué à chaque image). */
class NumText(private val pre: String = "", private val post: String = "") {
    private var k = Int.MIN_VALUE
    private var s = ""
    fun of(v: Int): String {
        if (v != k) { k = v; s = pre + v + post }
        return s
    }
}

/** Texte « nom · nombre°F » refait seulement quand le nom ou le nombre change. */
class NameNumText(private val mid: String, private val post: String) {
    private var ka: String? = null
    private var kb = Int.MIN_VALUE
    private var s = ""
    fun of(a: String, b: Int): String {
        if (b != kb || a != ka) { ka = a; kb = b; s = a + mid + b + post }
        return s
    }
}

/** Le cadran de température, commun à l'accueil et à l'écran de la puff (centre 150,190). */
class GaugeDrawer {
    private val oval = RectF(150f - 96f, 190f - 96f, 150f + 96f, 190f + 96f)
    private val targetTxt = NumText()

    private fun ang(t: Float) = 135f + 270f * (t - 70f) / 490f
    fun px(t: Float, r: Float) = 150f + r * cos(Math.toRadians(ang(t).toDouble())).toFloat()
    fun py(t: Float, r: Float) = 190f + r * sin(Math.toRadians(ang(t).toDouble())).toFloat()

    /**
     * valueF : température affichée (-1 si inconnue). sweepPct : 0..1 de l'arc plein (-1 = automatique).
     * running : petite lumière qui fait le tour.
     */
    fun draw(c: Canvas, ink: Ink, acc: Int, t: Float, valueF: Float, sweepPct: Float, targetF: Int,
             running: Boolean, showTarget: Boolean) {
        c.drawCircle(150f, 190f, 80f, ink.line(0xFF0E1822.toInt(), 1f))
        c.drawArc(oval, 135f, 270f, false, ink.line(0xFF0C141D.toInt(), 9f))
        ink.neon(c, Pal.mul(acc, .55f), 6f, .5f) { cc, p -> cc.drawArc(oval, 135f, 270f, false, p) }

        val full = if (sweepPct >= 0f) sweepPct.coerceIn(0f, 1f)
                   else if (valueF > 75f) ((min(valueF, 560f) - 70f) / 490f) else 0f
        if (full > 0.004f) {
            val sw = 270f * full
            ink.neon(c, acc, 6f, 1f) { cc, p -> cc.drawArc(oval, 135f, sw, false, p) }
        }
        if (running) {
            val ph = (t / 10f) % 1f
            val s0 = 135f + 270f * ph
            val sw = min(37.8f, 405f - s0)
            ink.neon(c, acc, 6f, 1f) { cc, p -> cc.drawArc(oval, s0, sw, false, p) }
        }

        val litTo = if (sweepPct >= 0f) 70f + 490f * sweepPct.coerceIn(0f, 1f) else if (valueF >= 0f) valueF else 70f
        var tt = 70
        while (tt <= 560) {
            val major = (tt - 70) % 50 == 0
            val r2 = if (major) 116f else 111f
            val col = if (tt <= litTo) Pal.mul(acc, .9f) else if (major) 0xFF2A3C50.toInt() else 0xFF1A2634.toInt()
            c.drawLine(px(tt.toFloat(), 106f), py(tt.toFloat(), 106f), px(tt.toFloat(), r2), py(tt.toFloat(), r2),
                ink.line(col, if (major) 1.4f else 1f))
            tt += 10
        }

        if (showTarget && targetF > 0) {
            val tg = targetF.toFloat()
            ink.neon(c, acc, 4f, 1f) { cc, p -> cc.drawLine(px(tg, 86f), py(tg, 86f), px(tg, 106f), py(tg, 106f), p) }
            ink.text(c, targetTxt.of(targetF), px(tg, 128f), py(tg, 128f) + 4f, 11f, acc)
        }

        ink.text(c, "70°", 76f, 284f, 11f, Pal.DIM)
        ink.text(c, "560°", 224f, 284f, 11f, Pal.DIM)

        if (valueF >= 0f || sweepPct >= 0f) {
            val dv = if (sweepPct >= 0f) 70f + 490f * sweepPct.coerceIn(0f, 1f) else valueF.coerceIn(70f, 560f)
            val x = px(dv, 96f)
            val y = py(dv, 96f)
            ink.glowDot(c, x, y, 5f, acc)
            val f = (t % 2.4f) / 2.4f
            c.drawCircle(x, y, 5f + 10f * f, ink.line(Pal.a(acc, .7f * (1f - f)), 1f))
        }
    }
}

/** Fond commun : dégradé sombre + hexagones très discrets. */
class BackdropView(ctx: Context) : View(ctx) {
    private val bg = Paint()
    private val hexP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hex = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF080B11.toInt(), 0xFF05070B.toInt(), Shader.TileMode.CLAMP)
        val u = w / 300f
        hexP.style = Paint.Style.STROKE
        hexP.strokeWidth = max(1f, .8f * u)
        hexP.color = 0x16A6C4E2
        hex.reset()
        val rows = (h / (30f * u)).toInt() + 2
        val cols = (w / (34.64f * u)).toInt() + 2
        for (r in -1..rows) {
            for (ci in -1..cols) {
                val cx = ci * 34.64f * u + (if ((r and 1) == 1) 17.32f * u else 0f)
                val cy = r * 30f * u
                hex.moveTo(cx, cy - 20f * u)
                hex.lineTo(cx + 17.32f * u, cy - 10f * u)
                hex.lineTo(cx + 17.32f * u, cy + 10f * u)
                hex.lineTo(cx, cy + 20f * u)
                hex.lineTo(cx - 17.32f * u, cy + 10f * u)
                hex.lineTo(cx - 17.32f * u, cy - 10f * u)
                hex.close()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawPath(hex, hexP)
    }
}
