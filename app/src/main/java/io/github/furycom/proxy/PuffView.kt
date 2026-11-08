package io.github.furycom.proxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class PuffState {
    var kind = 0              // 0 chauffe, 1 prêt, 2 nettoyage, 3 fin de chauffe
    var tempF = 72
    var targetF = 510
    var name = ""
    var cleanP = 0f           // 0..100
    var battery = -1
    var charging = false
    var puffNo = 0
    var boostLeft = 0f
    var accent = Pal.DEF[1]
    var canBoost = true
    var devName = ""          // nom réel du Proxy (vide si inconnu)
}

/** L'écran « pendant la puff » : cadran qui monte, courbe, fumée, lueur de flammes, nettoyage. */
class PuffView(ctx: Context) : View(ctx) {
    var st = PuffState()
    var onStop: () -> Unit = {}
    var onBoost: () -> Unit = {}

    private class Sm(var x: Float, var y: Float, val r: Float, val vy: Float, val ph: Float, val a: Float, var life: Float)
    private class Fi(var x: Float, var y: Float, val r: Float, val vy: Float, var life: Float)
    private class Bu(var x: Float, var y: Float, val r: Float, val vy: Float, val ph: Float)
    private class Rg(var t: Float)

    private val ink = Ink()
    private val gauge = GaugeDrawer()
    private val rr = RectF()
    private val tmp = Path()
    private val pth = Path()
    private val rnd = java.util.Random()
    private var sx = 1f
    private var dxs = 0f
    private val H = 520f

    private var last = 0L
    private var time = 0f
    private var cur = -1f
    private var prevCur = 0f
    private val hist = FloatArray(60)
    private var hn = 0
    private var hAcc = 0f
    private var rate = 0f

    private val smoke = ArrayList<Sm>()
    private val fire = ArrayList<Fi>()
    private val rings = ArrayList<Rg>()
    private val bubs = ArrayList<Bu>()
    private var ringT = 0f

    private val blob: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        val cv = Canvas(it)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(32f, 32f, 32f, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        cv.drawCircle(32f, 32f, 32f, p)
    }
    private val smokeP = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xFFAFC3DC.toInt(), PorterDuff.Mode.SRC_IN)
    }
    private val fireP = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val glowP = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fireLut = Array(16) { k ->
        val l = k / 15f
        val g = (40f + 190f * Math.pow(l.toDouble(), 1.5).toFloat()).toInt()
        val b = (30f * l).toInt()
        PorterDuffColorFilter(Color.rgb(255, g, b), PorterDuff.Mode.SRC_IN)
    }
    private val haloP = Paint()
    private val floorP = Paint()
    private val fillP = Paint()
    private val dashP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x99 shl 24 or 0x7F95A8
        pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
    }
    private var haloKey = 0
    private var haloShader: Shader? = null
    private var floorShader: Shader? = null
    private var pressed = -1
    private val bStop = RectF(14.5f, 424.5f, 145.5f, 476.5f)
    private val bBoost = RectF(154.5f, 424.5f, 285.5f, 476.5f)
    private val panel = RectF(14.5f, 332.5f, 285.5f, 408.5f)

    // dégradés et textes gardés d'une image à l'autre (rendu identique, rien d'alloué à chaque image)
    private var curveKey = 0
    private var curveShader: Shader? = null
    private val glowShaders = arrayOfNulls<Shader>(256)
    private val sweepShader: Shader = LinearGradient(0f, 0f, 0f, 60f, Pal.a(Pal.CYAN, 0f), Pal.a(Pal.CYAN, .14f), Shader.TileMode.CLAMP)
    private val batTxt = NumText("Batterie ", " %")
    private val batChTxt = NumText("Batterie ", " % ⚡")
    private val puffTxt = NumText("Puff n° ")
    private val subTxt = NameNumText(" · ", "°F")
    private val bigTxt = NumText()
    private val readyTxt = NumText("Prêt dans ", " s")
    private val boostLineTxt = NumText("Boost · ", " s")
    private val purgeTxt = NumText("Purge ", " %")
    private val boostBtnTxt = NumText("Boost ", " s")

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sx = min(w / 300f, h / H)
        dxs = (w - 300f * sx) / 2f
    }

    private fun rand() = rnd.nextFloat()

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) .016f else min(.05f, (now - last) / 1e9f)
        last = now
        time += dt
        val s = st
        if (cur < 0f) { cur = s.tempF.toFloat(); prevCur = cur }
        cur += (s.tempF - cur) * (1f - exp(-dt * 5f))
        hAcc += dt
        if (hAcc > .5f) {
            hAcc = 0f
            if (hn < 60) { hist[hn++] = cur } else { System.arraycopy(hist, 1, hist, 0, 59); hist[59] = cur }
            val inst = (cur - prevCur) / .5f
            rate = rate * .7f + inst * .3f
            prevCur = cur
        }

        val clean = s.kind == 2
        val acc = if (clean) Pal.CYAN else s.accent

        c.save()
        c.translate(dxs, 0f)
        c.scale(sx, sx)
        drawHalo(c, acc)
        drawParticles(c, dt, clean, s)
        drawTop(c, acc, s)
        c.save()
        c.translate(0f, 30f)
        val pct = if (clean) s.cleanP / 100f else ((cur - 70f) / 490f).coerceIn(0f, 1f)
        gauge.draw(c, ink, acc, time, cur, pct, s.targetF, false, !clean)
        drawBigTemp(c, s)
        c.restore()
        drawPanel(c, acc, s)
        drawButtons(c, acc, s)
        ink.text(c, if (s.battery >= 0) (if (s.charging) batChTxt else batTxt).of(s.battery) else "", 14f, 503f, 11f, Pal.DIM, Paint.Align.LEFT)
        ink.text(c, puffTxt.of(s.puffNo), 286f, 503f, 11f, Pal.DIM, Paint.Align.RIGHT)
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun drawHalo(c: Canvas, acc: Int) {
        if (acc != haloKey || haloShader == null) {
            haloKey = acc
            haloShader = RadialGradient(150f, 220f, 215f,
                intArrayOf(Pal.a(acc, .30f), Pal.a(acc, .10f), Pal.a(acc, 0f)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            floorShader = RadialGradient(0f, 0f, 190f, intArrayOf(Pal.a(acc, .25f), Pal.a(acc, 0f)), null, Shader.TileMode.CLAMP)
        }
        val br = .875f + .125f * sin(time * 6.2832f / 6f)
        haloP.shader = haloShader
        haloP.alpha = (br * 255f).toInt()
        c.drawCircle(150f, 220f, 215f, haloP)
        c.save()
        c.translate(150f, 450f)
        c.scale(1f, .37f)
        floorP.shader = floorShader
        floorP.alpha = (br * 255f).toInt()
        c.drawCircle(0f, 0f, 190f, floorP)
        c.restore()
    }

    private fun drawParticles(c: Canvas, dt: Float, clean: Boolean, s: PuffState) {
        val f60 = dt * 60f
        val sm = if (clean) 0f else ((cur - 230f) / 280f).coerceIn(0f, 1f)
        if (sm > .02f && rand() < sm * .14f * f60 && smoke.size < 46) {
            smoke.add(Sm(10f + rand() * 280f, 560f, 45f + rand() * 55f, .25f + rand() * .45f, rand() * 6.28f, .05f + rand() * .06f, 1f))
        }
        var i = smoke.size - 1
        while (i >= 0) {
            val p = smoke[i]
            p.y -= p.vy * f60
            p.x += sin(p.ph + time * .6f) * .18f
            p.life -= dt * .16f
            if (p.life <= 0f || p.y < -120f) { smoke.removeAt(i); i--; continue }
            val al = p.a * sm * min(1f, p.life * 2.2f) * (if (s.kind == 1) 1.35f else 1f)
            smokeP.alpha = (al * 255f).toInt().coerceIn(0, 255)
            rr.set(p.x - p.r, p.y - p.r, p.x + p.r, p.y + p.r)
            c.drawBitmap(blob, null, rr, smokeP)
            i--
        }

        val f = if (clean) 0f else ((cur - 440f) / 90f).coerceIn(0f, 1.3f)
        if (f > .02f) {
            val ga = (f * .22f * 255f).toInt().coerceIn(0, 255)
            var gs = glowShaders[ga]
            if (gs == null) {
                gs = LinearGradient(0f, 520f, 0f, 340f, Color.argb(ga, 255, 90, 20), Color.argb(0, 255, 90, 20), Shader.TileMode.CLAMP)
                glowShaders[ga] = gs
            }
            glowP.shader = gs
            c.drawRect(0f, 340f, 300f, 520f, glowP)
            val n = ceil(f * 2f).toInt()
            for (k in 0 until n) if (rand() < .5f * min(1f, f60) && fire.size < 70) {
                fire.add(Fi(150f + (rand() - .5f) * 330f, 530f, 30f + rand() * 40f, .5f + rand() * 1.1f, 1f))
            }
        }
        i = fire.size - 1
        while (i >= 0) {
            val p = fire[i]
            p.y -= p.vy * f60
            p.life -= dt * .55f
            if (p.life <= 0f) { fire.removeAt(i); i--; continue }
            val al = min(.2f, f * .16f * p.life)
            fireP.alpha = (al * 255f).toInt().coerceIn(0, 255)
            fireP.colorFilter = fireLut[(p.life * 15f).toInt().coerceIn(0, 15)]
            rr.set(p.x - p.r, p.y - p.r, p.x + p.r, p.y + p.r)
            c.drawBitmap(blob, null, rr, fireP)
            i--
        }

        if (clean) {
            ringT += dt
            if (ringT > 1.1f) { ringT = 0f; rings.add(Rg(0f)) }
            i = rings.size - 1
            while (i >= 0) {
                val r = rings[i]
                r.t += dt
                if (r.t > 3f) { rings.removeAt(i); i--; continue }
                val rad = r.t / 3f * 200f
                val al = .42f * (1f - r.t / 3f)
                c.drawCircle(150f, 220f, rad, ink.line(Pal.a(Pal.CYAN, al), 2f))
                c.drawCircle(150f, 220f, rad, ink.line(Pal.a(Pal.CYAN, al * .35f), 7f))
                i--
            }
            if (rand() < .12f * min(1f, f60) && bubs.size < 30) {
                bubs.add(Bu(rand() * 300f, 530f, 2f + rand() * 5f, .6f + rand() * .9f, rand() * 6.28f))
            }
            i = bubs.size - 1
            while (i >= 0) {
                val b = bubs[i]
                b.y -= b.vy * f60
                b.x += sin(b.ph + time * 2f) * .3f
                if (b.y < -10f) { bubs.removeAt(i); i--; continue }
                c.drawCircle(b.x, b.y, b.r, ink.fill(Pal.a(Pal.CYAN, .06f)))
                c.drawCircle(b.x, b.y, b.r, ink.line(Pal.a(Pal.CYAN, .45f), 1f))
                i--
            }
            val sy = (time * 70f) % 580f - 30f
            // même dégradé (0..60) déplacé à la bonne hauteur au lieu d'en créer un neuf
            fillP.shader = sweepShader
            c.save()
            c.translate(0f, sy - 60f)
            c.drawRect(0f, 0f, 300f, 60f, fillP)
            c.restore()
            fillP.shader = null
            fillP.color = Pal.a(Pal.CYAN, .4f)
            c.drawRect(0f, sy, 300f, sy + 1.5f, fillP)
        } else {
            rings.clear(); bubs.clear()
        }
    }

    private fun drawTop(c: Canvas, acc: Int, s: PuffState) {
        ink.glowDot(c, 15f, 28f, 3.5f, acc, .775f + .225f * sin(time * 6.2832f / 3.2f))
        if (s.devName.isNotEmpty()) ink.text(c, s.devName, 26f, 32f, 12f, Pal.SOFT, Paint.Align.LEFT, Fonts.REG, .08f)
        c.drawLine(14f, 46.5f, 286f, 46.5f, ink.line(0x22FFFFFF, 1f))
        val label = when (s.kind) {
            0 -> "CHAUFFE"
            1 -> if (s.boostLeft > 0f) "BOOST" else "PRÊT"
            2 -> "NETTOYAGE"
            else -> "FIN"
        }
        ink.glowText(c, label, 150f, 74f, 12f, acc, Paint.Align.CENTER, Fonts.REG, .33f)
        val sub = if (s.kind == 2) "Cycle de purge" else subTxt.of(s.name, s.targetF)
        ink.text(c, sub, 150f, 92f, 11f, Pal.MUT)
    }

    private fun drawBigTemp(c: Canvas, s: PuffState) {
        val big = bigTxt.of(Math.round(cur))
        val wb = ink.width(big, 58f, Fonts.LIGHT)
        val wu = ink.width("°F", 20f, Fonts.LIGHT)
        val x0 = 150f - (wb + 3f + wu) / 2f
        ink.text(c, big, x0, 208f, 58f, Pal.BIG, Paint.Align.LEFT, Fonts.LIGHT)
        ink.text(c, "°F", x0 + wb + 3f, 208f, 20f, Pal.MUT, Paint.Align.LEFT, Fonts.LIGHT)
        val line = when (s.kind) {
            0 -> {
                val rem = (s.targetF - 4f - cur)
                if (rate > 1.2f && rem > 0f) readyTxt.of(max(1, ceil(rem / rate).toInt()))
                else if (rem <= 0f) "Presque prêt" else "Chauffe en cours…"
            }
            1 -> if (s.boostLeft > 0f) boostLineTxt.of(ceil(s.boostLeft).toInt()) else "Prêt, tu peux tirer"
            2 -> purgeTxt.of(s.cleanP.toInt())
            else -> "Fin de chauffe"
        }
        ink.text(c, line, 150f, 233f, 13f, Pal.BTN, Paint.Align.CENTER, Fonts.REG, .04f)
    }

    private fun gx(i: Int) = 24f + 252f * i / 59f
    private fun gy(v: Float) = 400f - (v.coerceIn(60f, 570f) - 60f) / 510f * 42f

    private fun drawPanel(c: Canvas, acc: Int, s: PuffState) {
        ink.glass(c, panel)
        ink.text(c, "Température en direct", 24f, 350f, 11f, Pal.DIM, Paint.Align.LEFT, Fonts.REG, .12f)
        val ty = gy(s.targetF.toFloat())
        c.drawLine(24f, ty, 276f, ty, dashP)
        if (hn > 1) {
            pth.reset()
            val off = 60 - hn
            for (k in 0 until hn) {
                val x = gx(k + off)
                val y = gy(hist[k])
                if (k == 0) pth.moveTo(x, y) else pth.lineTo(x, y)
            }
            tmp.set(pth)
            tmp.lineTo(gx(59), 400f)
            tmp.lineTo(gx(off), 400f)
            tmp.close()
            if (acc != curveKey || curveShader == null) {
                curveKey = acc
                curveShader = LinearGradient(0f, 358f, 0f, 400f, Pal.a(acc, .30f), Pal.a(acc, 0f), Shader.TileMode.CLAMP)
            }
            fillP.shader = curveShader
            c.drawPath(tmp, fillP)
            fillP.shader = null
            ink.neon(c, acc, 2f, .8f) { cc, p -> cc.drawPath(pth, p) }
        }
    }

    private fun drawButtons(c: Canvas, acc: Int, s: PuffState) {
        // Arrêter
        ink.glass(c, bStop)
        if (pressed == 0) c.drawRoundRect(bStop, 3f, 3f, ink.fill(0x14FFFFFF))
        val tw = ink.width("Arrêter", 15f, Fonts.REG, .05f)
        val gx0 = bStop.centerX() - (12f + 10f + tw) / 2f
        rr.set(gx0, 443f, gx0 + 12f, 455f)
        c.drawRoundRect(rr, 1.5f, 1.5f, ink.line(Pal.BTN, 1.6f))
        ink.text(c, "Arrêter", gx0 + 22f, 455f, 15f, Pal.BTN, Paint.Align.LEFT, Fonts.REG, .05f)

        // Boost
        val on = s.canBoost
        // tampon hors écran seulement quand le bouton est grisé (à pleine opacité il ne change rien)
        val save = if (on) c.save()
            else c.saveLayerAlpha(bBoost.left - 20f, bBoost.top - 20f, bBoost.right + 20f, bBoost.bottom + 20f, 80)
        c.drawRoundRect(bBoost, 3f, 3f, ink.fill(Pal.a(acc, .10f)))
        ink.brushed(c, bBoost)
        ink.neon(c, acc, 2.8f, 1f) { cc, p -> cc.drawRoundRect(bBoost, 3f, 3f, p) }
        if (on) {
            val per = 2f * (bBoost.width() + bBoost.height())
            val d = per * .10f
            val s0 = ((time / 5f) % 1f) * per
            drawPerimeter(c, bBoost, s0, d)
            if (s0 + d > per) drawPerimeter(c, bBoost, 0f, s0 + d - per)
        }
        if (pressed == 1 && on) c.drawRoundRect(bBoost, 3f, 3f, ink.fill(0x18FFFFFF))
        val label = if (s.boostLeft > 0f) boostBtnTxt.of(ceil(s.boostLeft).toInt()) else "Boost"
        val bw = ink.width(label, 16f, Fonts.MED, .09f)
        val gx1 = bBoost.centerX() - (14f + 10f + bw) / 2f
        tmp.reset()
        tmp.moveTo(gx1 + 8f, 440f); tmp.lineTo(gx1, 452f); tmp.lineTo(gx1 + 6f, 452f)
        tmp.lineTo(gx1 + 4f, 462f); tmp.lineTo(gx1 + 14f, 449f); tmp.lineTo(gx1 + 8f, 449f); tmp.lineTo(gx1 + 11f, 440f); tmp.close()
        ink.glowPath(c, tmp, acc)
        ink.glowText(c, label, gx1 + 24f, 456f, 16f, acc, Paint.Align.LEFT, Fonts.MED, .09f)
        c.restoreToCount(save)
    }

    /** Petit trait blanc qui longe le bord du bouton. */
    private fun drawPerimeter(c: Canvas, r: RectF, from: Float, len: Float) {
        tmp.reset()
        val w = r.width()
        val h = r.height()
        var pos = from
        var left = len
        var first = true
        while (left > 0f && pos < 2f * (w + h)) {
            val seg: Float
            val x0: Float; val y0: Float; val x1: Float; val y1: Float
            if (pos < w) { seg = min(left, w - pos); x0 = r.left + pos; y0 = r.top; x1 = x0 + seg; y1 = y0 }
            else if (pos < w + h) { val q = pos - w; seg = min(left, h - q); x0 = r.right; y0 = r.top + q; x1 = x0; y1 = y0 + seg }
            else if (pos < 2f * w + h) { val q = pos - w - h; seg = min(left, w - q); x0 = r.right - q; y0 = r.bottom; x1 = x0 - seg; y1 = y0 }
            else { val q = pos - 2f * w - h; seg = min(left, h - q); x0 = r.left; y0 = r.bottom - q; x1 = x0; y1 = y0 - seg }
            if (first) { tmp.moveTo(x0, y0); first = false }
            tmp.lineTo(x1, y1)
            pos += seg
            left -= seg
            if (seg <= 0f) break
        }
        c.drawPath(tmp, ink.line(Pal.a(Pal.WHITE, .9f), 2.4f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lx = (e.x - dxs) / sx
        val ly = e.y / sx
        val h = if (bStop.contains(lx, ly)) 0 else if (bBoost.contains(lx, ly)) 1 else -1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = h; return h >= 0 }
            MotionEvent.ACTION_UP -> {
                val was = pressed
                pressed = -1
                if (was >= 0 && was == h) {
                    performClick()
                    if (h == 0) onStop() else if (st.canBoost) onBoost()
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
