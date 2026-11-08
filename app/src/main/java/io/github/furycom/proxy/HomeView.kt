package io.github.furycom.proxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class HomeState {
    var ready = false
    var status = ""
    var battery = -1
    var charging = false
    var tempF = -1
    var stateText = ""
    var active = 0
    val names = arrayOf("Saveur", "Équilibré", "Vapeur", "Max")
    val temps = intArrayOf(490, 510, 530, 545)
    val colors = Pal.DEF.copyOf()
    var since = -1
    var due = false
}

/** L'écran d'accueil, dessiné d'un bloc (coordonnées 300 x 590, mises à l'échelle). */
class HomeView(ctx: Context) : View(ctx) {
    var st = HomeState()
    var onSelect: (Int) -> Unit = {}
    var onStart: () -> Unit = {}
    var onClean: () -> Unit = {}
    var onOff: () -> Unit = {}
    var onCounter: () -> Unit = {}

    private val ink = Ink()
    private val gauge = GaugeDrawer()
    private val rr = RectF()
    private val rr2 = RectF()
    private val tmp = Path()
    private val ico = Path()
    private val btn = RectF(14.5f, 404.5f, 285.5f, 455.5f)
    private val btnPath = Path().apply { addRoundRect(btn, 3f, 3f, Path.Direction.CW) }
    private val pm = PathMeasure(btnPath, true)
    private val clean = RectF(14.5f, 470.5f, 145.5f, 501.5f)
    private val off = RectF(154.5f, 470.5f, 285.5f, 501.5f)
    private val panel = RectF(14.5f, 514.5f, 285.5f, 576.5f)
    private val cardR = Array(4) { RectF(14.5f + 70f * it, 318.5f, 14.5f + 70f * it + 61f, 391.5f) }
    private val haloP = Paint()
    private val floorP = Paint()
    private val divP = Paint()
    private var haloKey = 0
    private var haloShader: Shader? = null
    private var floorShader: Shader? = null
    private var pressed = -1
    private var sx = 1f
    private var dxs = 0f

    // textes gardés d'une image à l'autre (refaits seulement quand ils changent)
    private var stKey: String? = null
    private var stTxt = ""
    private val batTxt = NumText("", " %")
    private val bigTxt = NumText()
    private val chipTxt = NameNumText(" · ", "°F")
    private val cardTxt = Array(4) { NumText() }
    private val sinceTxt = NumText("", " / 100")
    private val leftTxt = NumText("Encore ", " puffs avant le prochain")

    init {
        divP.shader = LinearGradient(14f, 0f, 286f, 0f, intArrayOf(0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        divP.strokeWidth = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sx = min(w / 300f, h / 590f)
        dxs = (w - 300f * sx) / 2f
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val s = st
        val ai = s.active.coerceIn(0, 3)
        val acc = if (s.ready) s.colors[ai] else Pal.OFF
        c.save()
        c.translate(dxs, 0f)
        c.scale(sx, sx)
        drawHalo(c, acc, t)
        drawHeader(c, acc, t)
        drawGauge(c, acc, t)
        drawCards(c, ai)
        drawStart(c, acc, t)
        drawSmall(c)
        drawPanel(c, acc, t)
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun drawHalo(c: Canvas, acc: Int, t: Float) {
        if (acc != haloKey || haloShader == null) {
            haloKey = acc
            haloShader = RadialGradient(150f, 190f, 215f,
                intArrayOf(Pal.a(acc, .30f), Pal.a(acc, .10f), Pal.a(acc, 0f)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            floorShader = RadialGradient(0f, 0f, 190f, intArrayOf(Pal.a(acc, .25f), Pal.a(acc, 0f)), null, Shader.TileMode.CLAMP)
        }
        val br = .875f + .125f * sin(t * 6.2832f / 6f)
        haloP.shader = haloShader
        haloP.alpha = (br * 255f).toInt()
        c.drawCircle(150f, 190f, 215f, haloP)
        c.save()
        c.translate(150f, 450f)
        c.scale(1f, .37f)
        floorP.shader = floorShader
        floorP.alpha = (br * 255f).toInt()
        c.drawCircle(0f, 0f, 190f, floorP)
        c.restore()
    }

    private fun drawHeader(c: Canvas, acc: Int, t: Float) {
        val s = st
        val pulse = .775f + .225f * sin(t * 6.2832f / 3.2f)
        ink.glowDot(c, 15f, 28f, 3.5f, if (s.ready) acc else Pal.RED, pulse)
        if (s.status != stKey) {
            stKey = s.status
            var txt = if (s.status.isEmpty()) "Proxy" else s.status
            if (ink.width(txt, 12f, Fonts.REG, .08f) > 210f) {
                while (txt.length > 4 && ink.width("$txt…", 12f, Fonts.REG, .08f) > 210f) txt = txt.dropLast(1)
                txt += "…"
            }
            stTxt = txt
        }
        ink.text(c, stTxt, 26f, 32f, 12f, Pal.SOFT, Paint.Align.LEFT, Fonts.REG, .08f)
        if (s.battery >= 0) {
            ink.text(c, batTxt.of(s.battery), 252f, 32f, 12f, Pal.TXT, Paint.Align.RIGHT)
            rr.set(258.5f, 21.5f, 282.5f, 34.5f)
            c.drawRoundRect(rr, 2.5f, 2.5f, ink.line(Pal.MUT, 1f))
            c.drawRect(283.5f, 25f, 286f, 31f, ink.fill(Pal.MUT))
            val bc = if (s.battery < 20) Pal.RED else acc
            val fw = 20f * (s.battery.coerceIn(0, 100) / 100f)
            if (fw > .5f) {
                rr.set(260.5f, 23.5f, 260.5f + fw, 32.5f)
                c.drawRoundRect(rr, 1f, 1f, ink.fill(bc))
            }
            if (s.charging) {
                ico.reset()
                ico.moveTo(272f, 23f); ico.lineTo(266f, 29f); ico.lineTo(269.5f, 29f)
                ico.lineTo(267.5f, 33f); ico.lineTo(274f, 27f); ico.lineTo(270.5f, 27f); ico.close()
                c.drawPath(ico, ink.fill(Pal.WHITE))
            }
        }
        divP.alpha = 150
        c.drawLine(14f, 46.5f, 286f, 46.5f, divP)
    }

    private fun drawGauge(c: Canvas, acc: Int, t: Float) {
        val s = st
        val ai = s.active.coerceIn(0, 3)
        val tf = if (s.tempF >= 0) s.tempF.toFloat() else -1f
        gauge.draw(c, ink, acc, t, tf, -1f, s.temps[ai], true, true)

        val big = if (s.tempF >= 0) bigTxt.of(s.tempF) else "--"
        val wb = ink.width(big, 58f, Fonts.LIGHT)
        val wu = ink.width("°F", 20f, Fonts.LIGHT)
        val x0 = 150f - (wb + 3f + wu) / 2f
        ink.text(c, big, x0, 208f, 58f, Pal.BIG, Paint.Align.LEFT, Fonts.LIGHT)
        ink.text(c, "°F", x0 + wb + 3f, 208f, 20f, Pal.MUT, Paint.Align.LEFT, Fonts.LIGHT)
        val label = if (s.ready) s.stateText else "Hors connexion"
        ink.text(c, label, 150f, 232f, 12f, Pal.MUT, Paint.Align.CENTER, Fonts.REG, .15f)

        val chip = chipTxt.of(s.names[ai], s.temps[ai])
        val cw = ink.width(chip, 12f) + 26f
        rr.set(150f - cw / 2f, 244.5f, 150f + cw / 2f, 267.5f)
        c.drawRoundRect(rr, 11.5f, 11.5f, ink.fill(Pal.a(acc, .10f)))
        c.drawRoundRect(rr, 11.5f, 11.5f, ink.line(Pal.a(acc, .6f), 1f))
        ink.text(c, chip, 150f, 260f, 12f, acc)

        ink.text(c, "Mode de chauffe", 14f, 311f, 11f, Pal.DIM, Paint.Align.LEFT, Fonts.REG, .15f)
    }

    private fun drawCards(c: Canvas, ai: Int) {
        val s = st
        for (i in 0..3) {
            val r = cardR[i]
            val col = s.colors[i]
            val on = i == ai
            if (on) {
                c.drawRoundRect(r, 3f, 3f, ink.fill(Pal.a(col, .08f)))
                ink.brushed(c, r)
                ink.neon(c, col, 1.6f, .8f) { cc, p -> cc.drawRoundRect(r, 3f, 3f, p) }
            } else {
                ink.glass(c, r)
            }
            if (pressed == i) c.drawRoundRect(r, 3f, 3f, ink.fill(0x14FFFFFF))
            val y = r.top
            ink.neon(c, col, 3.5f, 1f) { cc, p -> cc.drawLine(r.left + 4f, y + 2.8f, r.right - 4f, y + 2.8f, p) }
            val cx = r.left + 30.5f
            ink.text(c, s.names[i], cx, y + 23.5f, 11f, if (on) col else Pal.MUT)
            ink.text(c, cardTxt[i].of(s.temps[i]), cx, y + 47.5f, 18f, if (on) col else Pal.TXT)
            ink.text(c, "°F", cx, y + 63.5f, 11f, Pal.DIM)
        }
    }

    private fun drawStart(c: Canvas, acc: Int, t: Float) {
        c.drawRoundRect(btn, 3f, 3f, ink.fill(Pal.a(acc, .09f)))
        ink.brushed(c, btn)
        // reflet qui passe
        val ph = (t % 8f) / 8f
        if (ph < .3f) {
            val xs = -60f + ph / .3f * 400f
            c.save()
            c.clipPath(btnPath)
            tmp.reset()
            tmp.moveTo(xs, 404.5f); tmp.lineTo(xs + 26f, 404.5f); tmp.lineTo(xs + 7.4f, 455.5f); tmp.lineTo(xs - 18.6f, 455.5f); tmp.close()
            c.drawPath(tmp, ink.fill(0x12FFFFFF))
            c.restore()
        }
        ink.neon(c, acc, 2.6f, 1f) { cc, p -> cc.drawRoundRect(btn, 3f, 3f, p) }
        // lumière blanche qui fait le tour
        val len = pm.length
        val d = len * .08f
        val s0 = ((t / 7f) % 1f) * len
        tmp.reset()
        pm.getSegment(s0, min(len, s0 + d), tmp, true)
        if (s0 + d > len) pm.getSegment(0f, s0 + d - len, tmp, true)
        c.drawPath(tmp, ink.line(Pal.a(Pal.WHITE, .9f), 2.4f))
        if (pressed == 4) c.drawRoundRect(btn, 3f, 3f, ink.fill(0x18FFFFFF))

        val tw = ink.width("Démarrer", 16f, Fonts.MED, .11f)
        val gx = 150f - (14f + 12f + tw) / 2f
        ico.reset()
        ico.moveTo(gx, 421f); ico.lineTo(gx, 439f); ico.lineTo(gx + 14f, 430f); ico.close()
        ink.glowPath(c, ico, acc)
        ink.glowText(c, "Démarrer", gx + 26f, 436f, 16f, acc, Paint.Align.LEFT, Fonts.MED, .11f)
    }

    private fun drawSmall(c: Canvas) {
        // Nettoyage
        ink.glass(c, clean)
        if (pressed == 5) c.drawRoundRect(clean, 3f, 3f, ink.fill(0x14FFFFFF))
        var tw = ink.width("Nettoyage", 12f)
        var gx = clean.centerX() - (14f + 8f + tw) / 2f
        val cx = gx + 7f
        val cy = 486f
        ico.reset()
        ico.moveTo(cx, cy - 6f); ico.lineTo(cx + 2f, cy - 2f); ico.lineTo(cx + 6f, cy); ico.lineTo(cx + 2f, cy + 2f)
        ico.lineTo(cx, cy + 6f); ico.lineTo(cx - 2f, cy + 2f); ico.lineTo(cx - 6f, cy); ico.lineTo(cx - 2f, cy - 2f); ico.close()
        c.drawPath(ico, ink.line(Pal.SOFT, 1.1f))
        ink.text(c, "Nettoyage", gx + 22f, 490f, 12f, Pal.BTN, Paint.Align.LEFT)
        // Éteindre
        ink.glass(c, off)
        if (pressed == 6) c.drawRoundRect(off, 3f, 3f, ink.fill(0x14FFFFFF))
        tw = ink.width("Éteindre", 12f)
        gx = off.centerX() - (14f + 8f + tw) / 2f
        val ox = gx + 7f
        rr.set(ox - 5.5f, cy - 5.5f, ox + 5.5f, cy + 5.5f)
        c.drawArc(rr, -60f, 300f, false, ink.line(Pal.SOFT, 1.2f))
        c.drawLine(ox, cy - 8f, ox, cy, ink.line(Pal.SOFT, 1.2f))
        ink.text(c, "Éteindre", gx + 22f, 490f, 12f, Pal.BTN, Paint.Align.LEFT)
    }

    private fun drawPanel(c: Canvas, acc: Int, t: Float) {
        val s = st
        ink.glass(c, panel)
        if (pressed == 7) c.drawRoundRect(panel, 3f, 3f, ink.fill(0x10FFFFFF))
        ink.text(c, "Nettoyage complet", 24f, 534f, 12f, Pal.SOFT, Paint.Align.LEFT)
        ink.text(c, if (s.since >= 0) sinceTxt.of(s.since) else "— / 100", 276f, 534f, 12f, Pal.TXT, Paint.Align.RIGHT)
        val col = if (s.due) Pal.WARN else acc
        val filled = if (s.since < 0) 0 else (s.since * 20 / 100).coerceIn(0, 20)
        for (i in 0 until 20) {
            val x = 24f + i * 12.75f
            rr.set(x, 540f, x + 9.75f, 548f)
            if (i < filled) {
                rr2.set(rr); rr2.inset(-2f, -2f)
                c.drawRoundRect(rr2, 2f, 2f, ink.fill(Pal.a(col, .18f)))
                c.drawRoundRect(rr, 1f, 1f, ink.fill(col))
            } else if (i == filled) {
                val k = .25f + .55f * (.5f + .5f * sin(t * 6.2832f / 3f))
                c.drawRoundRect(rr, 1f, 1f, ink.fill(Pal.a(col, k)))
            } else {
                c.drawRoundRect(rr, 1f, 1f, ink.fill(0xFF141E29.toInt()))
            }
        }
        if (s.due) ink.text(c, "Nettoyage complet à faire", 24f, 566f, 11f, Pal.WARN, Paint.Align.LEFT)
        else if (s.since >= 0) ink.text(c, leftTxt.of((100 - s.since).coerceAtLeast(0)), 24f, 566f, 11f, Pal.DIM, Paint.Align.LEFT)
        else ink.text(c, "Compteur pas encore lu", 24f, 566f, 11f, Pal.DIM, Paint.Align.LEFT)
    }

    private fun hit(lx: Float, ly: Float): Int {
        for (i in 0..3) if (cardR[i].contains(lx, ly)) return i
        if (btn.contains(lx, ly)) return 4
        if (clean.contains(lx, ly)) return 5
        if (off.contains(lx, ly)) return 6
        if (panel.contains(lx, ly)) return 7
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lx = (e.x - dxs) / sx
        val ly = e.y / sx
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(lx, ly)
                return pressed >= 0
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(lx, ly)
                val was = pressed
                pressed = -1
                if (was >= 0 && h == was) {
                    performClick()
                    when (h) {
                        0, 1, 2, 3 -> onSelect(h)
                        4 -> onStart()
                        5 -> onClean()
                        6 -> onOff()
                        7 -> onCounter()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
