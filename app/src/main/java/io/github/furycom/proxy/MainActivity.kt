package io.github.furycom.proxy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var link: PuffcoLink
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("puffco", 0) }

    // accueil + écran de puff
    private lateinit var home: HomeView
    private lateinit var puff: PuffView
    private val tabTexts = ArrayList<TextView>()
    private val tabBars = ArrayList<View>()
    private var curTab = 0
    private var lastAccent = 0

    // options
    private lateinit var boostTempTv: TextView
    private lateinit var boostTimeTv: TextView
    private lateinit var stealthSw: Switch
    private lateinit var lanternSw: Switch
    private lateinit var lightSeek: SeekBar
    private val intnRows = ArrayList<LinearLayout>()
    private val intnSeeks = ArrayList<SeekBar>()
    private lateinit var infoTv: TextView
    private lateinit var puffAdjTv: TextView
    private var updating = false

    private lateinit var pages: List<View>

    // nettoyage et boost
    private var cleaning = false
    private var cleanStart = 0L
    private var activeSince = 0L
    private var boostUntil = 0L

    private val defNames = arrayOf("Saveur", "Équilibré", "Vapeur", "Max")
    private val defTemps = intArrayOf(490, 510, 530, 545)
    private var appVersion = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        window.statusBarColor = 0xFF080B11.toInt()
        window.navigationBarColor = Theme.BG
        buildUi()
        link = Hub.get(this)
        link.onChange = { h.post { refresh() } }
        askPermissions()
    }

    private fun startAll() {
        try {
            startForegroundService(android.content.Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {
        }
        link.start()
    }

    private val uiTick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        link.onChange = { h.post { refresh() } }
        link.setBackground(false)
        if (hasPerms()) startAll()
        refresh()
        h.removeCallbacks(uiTick)
        h.postDelayed(uiTick, 500)
    }

    override fun onStop() {
        super.onStop()
        h.removeCallbacks(uiTick)
        // écran invisible : plus de rafraîchissement à chaque lecture, lecture du Proxy espacée
        link.onChange = { }
        link.setBackground(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            link.onChange = { }
            if (cleaning) link.stopSession()
            link.stop()
            stopService(android.content.Intent(this, KeepAliveService::class.java))
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Android 13+ : la notification du service se demande aussi, mais la refuser ne bloque pas le Bluetooth. */
    private fun askPermissions() {
        val ask = ArrayList<String>()
        if (!hasPerms()) ask.addAll(needed())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (hasPerms()) startAll()
        else link.live.status = "Permission refusée : sans elle, pas de Bluetooth"
        refresh()
    }

    // ---------------------------------------------------------------- interface

    private fun buildUi() {
        // l'écran se reconstruit (rotation, police, mode sombre) : on oublie les vues de l'ancien écran
        Accent.buttons.clear(); Accent.titles.clear(); Accent.seeks.clear(); Accent.switches.clear()
        val rootF = FrameLayout(this)
        rootF.addView(BackdropView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        home = HomeView(this)
        puff = PuffView(this)
        puff.visibility = View.GONE
        home.onSelect = { i -> if (link.live.ready) link.selectProfile(i) }
        home.onStart = { toggleHeat() }
        home.onClean = { toggleClean() }
        home.onOff = { confirmOff() }
        home.onCounter = { confirmCounter() }
        puff.onStop = { toggleHeat() }
        puff.onBoost = { doBoost() }
        val page0 = FrameLayout(this)
        page0.addView(home, FrameLayout.LayoutParams(-1, -1))
        page0.addView(puff, FrameLayout.LayoutParams(-1, -1))

        val frame = FrameLayout(this)
        pages = listOf(page0, optionsPage(), buttonsPage())
        for (p in pages) frame.addView(p, FrameLayout.LayoutParams(-1, -1))
        col.addView(frame, lp(-1, 0, 1f))

        val line = View(this)
        line.setBackgroundColor(0x22FFFFFF)
        col.addView(line, lp(-1, 1))
        val nav = hbox()
        nav.setBackgroundColor(0xCC05070B.toInt())
        val names = listOf("Accueil", "Options", "Mes boutons")
        for ((i, n) in names.withIndex()) {
            val cell = vbox()
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val bar = View(this)
            cell.addView(bar, lp(dp(40), dp(3), 0f, 0, 0, 0, dp(10)))
            val t = text(n, 14f, Theme.MUTED, true)
            t.gravity = Gravity.CENTER
            t.letterSpacing = .05f
            t.setPadding(0, 0, 0, dp(12))
            cell.addView(t, lp(-2, -2))
            cell.setOnClickListener { showPage(i) }
            tabTexts.add(t); tabBars.add(bar)
            nav.addView(cell, lp(0, -2, 1f))
        }
        col.addView(nav, lp(-1, -2))
        rootF.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootF)
        Accent.apply(Accent.color)
        showPage(0)
    }

    private fun styleTabs() {
        for (k in tabTexts.indices) {
            val on = k == curTab
            tabTexts[k].setTextColor(if (on) Accent.color else Theme.MUTED)
            tabTexts[k].setShadowLayer(if (on) dp(8).toFloat() else 0f, 0f, 0f, Accent.color)
            tabBars[k].background = if (on) shape(Accent.color, 2) else null
        }
    }

    private fun showPage(i: Int) {
        curTab = i
        for ((k, p) in pages.withIndex()) p.visibility = if (k == i) View.VISIBLE else View.GONE
        styleTabs()
        if (i == 1) refreshOptions(link.live)
    }

    private fun scroll(content: LinearLayout): ScrollView {
        val sv = ScrollView(this)
        content.setPadding(dp(18), dp(18), dp(18), dp(24))
        sv.addView(content, ViewGroup.LayoutParams(-1, -2))
        return sv
    }

    private fun section(c: LinearLayout, title: String) {
        val t = text(title, 12f, Accent.color, true)
        t.letterSpacing = .15f
        Accent.titles.add(t)
        c.addView(t, lp(-1, -2, 0f, 0, dp(20), 0, dp(8)))
    }

    private fun row(c: LinearLayout, label: String, right: View) {
        val r = hbox()
        r.background = glass(4)
        r.setPadding(dp(14), dp(12), dp(14), dp(12))
        r.addView(text(label, 15f), lp(0, -2, 1f))
        r.addView(right, lp(-2, -2))
        c.addView(r, lp(-1, -2, 0f, 0, 0, 0, dp(8)))
    }

    private fun stepper(minus: () -> Unit, plus: () -> Unit, value: TextView): LinearLayout {
        val s = hbox()
        s.addView(neonButton("−") { minus() }, lp(dp(48), dp(44)))
        value.gravity = Gravity.CENTER
        value.minWidth = dp(76)
        s.addView(value, lp(-2, -2))
        s.addView(neonButton("+") { plus() }, lp(dp(48), dp(44)))
        return s
    }

    private fun optionsPage(): View {
        val c = vbox()
        c.addView(text("Options", 24f, Theme.TEXT, true))

        section(c, "COMPTEUR DE PUFFS")
        puffAdjTv = text("", 15f, Theme.TEXT, true)
        row(c, "Puffs depuis le nettoyage complet (par 10)", stepper({ adjustSince(-10) }, { adjustSince(10) }, puffAdjTv))
        val adj1 = text("ajuste", 15f, Theme.MUTED)
        row(c, "Ajuster (par 1)", stepper({ adjustSince(-1) }, { adjustSince(1) }, adj1))

        section(c, "BOOST (mode actif)")
        boostTempTv = text("", 15f, Theme.TEXT, true)
        row(c, "Chaleur ajoutée", stepper(
            { if (link.live.ready) link.setBoostTemp((link.live.boostC10 - 10).coerceAtLeast(0)) },
            { if (link.live.ready) link.setBoostTemp((link.live.boostC10 + 10).coerceAtMost(300)) }, boostTempTv))
        boostTimeTv = text("", 15f, Theme.TEXT, true)
        row(c, "Durée ajoutée", stepper(
            { if (link.live.ready) link.setBoostTime((link.live.boostTimeRaw - 500).coerceAtLeast(0)) },
            { if (link.live.ready) link.setBoostTime((link.live.boostTimeRaw + 500).coerceAtMost(6000)) }, boostTimeTv))

        section(c, "LUMIÈRES")
        lightSeek = SeekBar(this)
        lightSeek.max = 255
        lightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { if (link.live.ready) link.setBrightnessAll(s.progress) }
        })
        Accent.seeks.add(lightSeek)
        val lr = vbox()
        lr.background = glass(4)
        lr.setPadding(dp(14), dp(12), dp(14), dp(12))
        lr.addView(text("Intensité générale", 15f))
        lr.addView(lightSeek, lp(-1, -2))
        c.addView(lr, lp(-1, -2, 0f, 0, 0, 0, dp(8)))
        for (i in 0..3) {
            val box = vbox()
            box.background = glass(4)
            box.setPadding(dp(14), dp(12), dp(14), dp(12))
            val tv = text("Mode ${i + 1}", 15f)
            tv.tag = "lbl"
            box.addView(tv)
            val sk = SeekBar(this)
            sk.max = 255
            sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { if (link.live.ready) link.setProfileIntensity(i, s.progress) }
            })
            Accent.seeks.add(sk)
            box.addView(sk, lp(-1, -2))
            intnRows.add(box); intnSeeks.add(sk)
            c.addView(box, lp(-1, -2, 0f, 0, 0, 0, dp(8)))
        }
        stealthSw = Switch(this)
        stealthSw.setOnCheckedChangeListener { _, on -> if (!updating && link.live.ready) link.setStealth(on) }
        Accent.switches.add(stealthSw)
        row(c, "Mode discret (lumières éteintes)", stealthSw)
        lanternSw = Switch(this)
        lanternSw.setOnCheckedChangeListener { _, on -> if (!updating && link.live.ready) link.setLantern(on) }
        Accent.switches.add(lanternSw)
        row(c, "Lanterne", lanternSw)

        section(c, "ÉTAT DU PROXY")
        infoTv = text("", 14f, Theme.MUTED)
        infoTv.background = glass(4)
        infoTv.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.addView(infoTv, lp(-1, -2))
        c.addView(text("Application non officielle, indépendante de Puffco", 13f, Theme.DIM),
            lp(-1, -2, 0f, 0, dp(10), 0, 0))
        return scroll(c)
    }

    private fun buttonsPage(): View {
        val c = vbox()
        c.addView(text("Mes boutons", 24f, Theme.TEXT, true))
        c.addView(text("Ce que fait chaque geste sur le Proxy, et le bouton qui le remplace ici.", 14f, Theme.MUTED),
            lp(-1, -2, 0f, 0, dp(4), 0, dp(12)))

        gesture(c, "Appui long 3 secondes", "Verrouiller / déverrouiller (et allumer / éteindre) le Proxy.",
            "Bientôt", false) { }
        gesture(c, "1 clic", "Change de mode : bleu, vert, rouge, blanc.", "Changer de mode", true) {
            if (link.live.ready) link.selectProfile((link.live.active + 1) % 4)
        }
        gesture(c, "2 clics", "Lance la chauffe du mode choisi.", "Chauffer", true) {
            if (link.live.ready) link.startSession()
        }
        gesture(c, "2 clics pendant la chauffe", "Boost : un peu plus chaud et plus longtemps.", "Boost", true) {
            doBoost()
        }
        gesture(c, "Appui long pendant la chauffe", "Annule la chauffe en cours.", "Annuler", true) {
            if (link.live.ready) link.stopSession()
        }
        gesture(c, "3 clics", "Montre la batterie : vert, orange ou rouge.", "Batterie", true) { showBattery() }
        c.addView(text("Aussi, sur le Proxy : 5 clics sur le mode blanc = température secrète, " +
            "3 clics pendant une session = lumières disco (à confirmer).", 13f, Theme.DIM),
            lp(-1, -2, 0f, 0, dp(10), 0, 0))
        return scroll(c)
    }

    private fun gesture(c: LinearLayout, title: String, desc: String, label: String, enabled: Boolean, act: () -> Unit) {
        val box = vbox()
        box.background = glass(4)
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.addView(text(title, 16f, Theme.TEXT, true))
        box.addView(text(desc, 14f, Theme.MUTED), lp(-1, -2, 0f, 0, dp(2), 0, dp(8)))
        if (enabled) {
            box.addView(neonButton(label) { act() }, lp(-1, -2))
        } else {
            val b = android.widget.Button(this)
            b.text = label
            b.isAllCaps = false
            b.stateListAnimator = null
            b.background = shape(Theme.CARD, 4, Theme.EDGE, 1)
            b.setTextColor(Theme.DIM)
            box.addView(b, lp(-1, -2))
        }
        c.addView(box, lp(-1, -2, 0f, 0, 0, 0, dp(10)))
    }

    private fun showBattery() {
        val b = link.live.battery
        if (b < 0) { toast("Batterie inconnue"); return }
        val color = if (b >= 50) "vert" else if (b >= 20) "orange" else "rouge"
        link.showBattery()
        toast("Batterie ${Math.round(b)} % — $color")
    }

    // ---------------------------------------------------------------- actions

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun confirmOff() {
        if (!link.live.ready) { toast("Pas connecté au Proxy"); return }
        AlertDialog.Builder(this)
            .setTitle("Éteindre le Proxy ?")
            .setPositiveButton("Éteindre") { _, _ -> if (link.live.ready) link.powerOff() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmCounter() {
        AlertDialog.Builder(this)
            .setTitle("Nettoyage complet fait ?")
            .setMessage("Le compteur de puffs repart à zéro.")
            .setPositiveButton("Oui, remettre à zéro") { _, _ -> resetPuffs() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun doBoost() {
        val l = link.live
        // Boost seulement pendant une chauffe : hors chauffe, rien n'est envoyé au Proxy
        if (!l.ready || cleaning || !l.heating) return
        link.boost()
        val ms = if (l.boostTimeRaw > 0) l.boostTimeRaw * 10L else 5000L
        boostUntil = System.currentTimeMillis() + ms
        refresh()
    }

    private fun toggleHeat() {
        val l = link.live
        if (!l.ready) { toast("Pas connecté au Proxy"); return }
        if (cleaning) { finishClean("Nettoyage annulé"); return }
        if (l.heating) link.stopSession() else link.startSession()
    }

    private fun toggleClean() {
        val l = link.live
        if (!l.ready) { toast("Pas connecté au Proxy"); return }
        if (cleaning) { finishClean("Nettoyage annulé"); return }
        cleaning = true
        cleanStart = System.currentTimeMillis()
        activeSince = 0L
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!l.heating) link.startSession()
        h.postDelayed(cleanTick, 500)
        refresh()
    }

    private val cleanTick = object : Runnable {
        override fun run() {
            if (!cleaning) return
            val l = link.live
            val now = System.currentTimeMillis()
            if (!l.ready) {
                cleaning = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refresh()
                return
            }
            if (l.heating && activeSince == 0L) activeSince = now
            when {
                l.heating && l.tott > 0 && l.elap >= l.tott / 2 -> { finishClean("Nettoyage terminé"); return }
                l.heating && l.tott <= 0 && now - activeSince > 12000 -> { finishClean("Nettoyage terminé"); return }
                now - cleanStart > 90000 -> { finishClean("Nettoyage arrêté (délai)"); return }
            }
            h.postDelayed(this, 400)
        }
    }

    private fun finishClean(msg: String) {
        cleaning = false
        link.stopSession()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toast(msg)
        refresh()
    }

    private fun baseline(): Long = prefs.getLong("baseline2", -1L)

    private fun since(): Long {
        if (link.live.trip >= 0) return link.live.trip
        val n = link.live.puffs
        val b = baseline()
        return if (n >= 0 && b >= 0) (n - b).coerceAtLeast(0) else 0
    }

    private fun adjustSince(delta: Int) {
        if (link.live.trip >= 0) { link.setTrip(link.live.trip + delta); return }
        val n = link.live.puffs
        if (n < 0) { toast("Compteur pas encore lu"); return }
        val target = (since() + delta).coerceIn(0, n)
        prefs.edit().putLong("baseline2", n - target).apply()
        refresh()
    }

    private fun resetPuffs() {
        if (link.live.trip >= 0) { link.setTrip(0); toast("Compteur remis à zéro"); return }
        val n = link.live.puffs
        if (n < 0) { toast("Compteur pas encore lu"); return }
        prefs.edit().putLong("baseline2", n).apply()
        toast("Compteur remis à zéro")
        refresh()
    }

    // ---------------------------------------------------------------- affichage

    private fun stateText(l: Live): String = when (l.state) {
        0, 1, 2 -> "Démarrage"; 3 -> "Éteint"; 4 -> "En veille"; 5 -> "Au repos"; 6 -> "Choix du mode"
        7 -> "Chauffe en cours…"; 8 -> "Prêt, tu peux tirer"; 9 -> "Fin de chauffe"
        10 -> "Affichage de la version"; 11 -> "Niveau de batterie"; 12 -> "Test usine"; 13 -> "Jumelage"
        else -> ""
    }

    private fun frName(n: String, i: Int): String {
        val s = n.lowercase()
        return when {
            s.isEmpty() -> defNames[i]
            s.contains("most") -> "Max"
            s.contains("strong") -> "Vapeur"
            s.contains("balanced") -> "Équilibré"
            s.contains("flavor") || s.contains("high") -> "Saveur"
            else -> n
        }
    }

    private fun cleanProgress(): Float {
        if (!cleaning) return 0f
        val l = link.live
        val now = System.currentTimeMillis()
        val p = when {
            l.heating && l.tott > 0 -> (l.elap / (l.tott / 2.0) * 100.0).toFloat()
            activeSince > 0L -> (now - activeSince) / 12000f * 100f
            else -> 0f
        }
        return p.coerceIn(0f, 100f)
    }

    private fun refresh() {
        val l = link.live
        val hs = home.st
        hs.ready = l.ready
        hs.status = l.status
        hs.battery = if (l.ready && l.battery >= 0) Math.round(l.battery).toInt() else -1
        hs.charging = l.charging in 0..1
        hs.tempF = if (l.ready && l.tempC10 > 0) P.c10ToF(l.tempC10) else -1
        hs.stateText = if (l.ready) stateText(l) + (if (cleaning) " · nettoyage" else "") else ""
        hs.active = l.active.coerceIn(0, 3)
        for (i in 0..3) {
            val pr = l.profiles[i]
            hs.names[i] = frName(pr.name, i)
            hs.temps[i] = if (pr.tempC10 > 0) P.c10ToF(pr.tempC10) else defTemps[i]
            hs.colors[i] = Pal.DEF[i]
        }

        // premier lancement : le compteur de nettoyage part du compteur actuel (0 puff depuis le nettoyage)
        if (baseline() < 0 && l.puffs >= 0) prefs.edit().putLong("baseline2", l.puffs.coerceAtLeast(0)).apply()
        val since = since()
        hs.since = if (l.puffs >= 0) since.toInt() else -1
        hs.due = since >= 100

        // couleur néon de toute l'application = couleur du mode actif
        val appAcc = hs.colors[hs.active]
        if (appAcc != lastAccent) {
            lastAccent = appAcc
            Accent.apply(appAcc)
            styleTabs()
        }

        // écran pendant la puff
        val ps = puff.st
        ps.kind = if (cleaning) 2 else when (l.state) { 8 -> 1; 9 -> 3; else -> 0 }
        ps.tempF = if (l.tempC10 > 0) P.c10ToF(l.tempC10) else hs.tempF.coerceAtLeast(72)
        ps.targetF = hs.temps[hs.active]
        ps.name = hs.names[hs.active]
        ps.cleanP = cleanProgress()
        ps.battery = hs.battery
        ps.charging = hs.charging
        ps.puffNo = since.toInt() + 1
        ps.boostLeft = ((boostUntil - System.currentTimeMillis()) / 1000f).coerceAtLeast(0f)
        ps.accent = appAcc
        ps.canBoost = !cleaning
        ps.devName = l.devName
        val showPuff = l.ready && (l.heating || cleaning)
        if ((puff.visibility == View.VISIBLE) != showPuff) {
            puff.visibility = if (showPuff) View.VISIBLE else View.GONE
            home.visibility = if (showPuff) View.GONE else View.VISIBLE
        }

        if (curTab == 1) refreshOptions(l)
    }

    private fun refreshOptions(l: Live) {
        val since = since()
        puffAdjTv.text = "$since"
        boostTempTv.text = "+${P.deltaC10ToF(l.boostC10)}°F"
        boostTimeTv.text = "+${Math.round(l.boostTimeRaw / 100.0)} s"
        updating = true
        stealthSw.isChecked = l.stealth
        lanternSw.isChecked = l.lantern
        updating = false
        // ne pas faire sauter un curseur pendant que le doigt le glisse
        if (!lightSeek.isPressed) lightSeek.progress = l.lbrt[0]
        for (i in 0..3) {
            val pr = l.profiles[i]
            ((intnRows[i].getChildAt(0)) as TextView).text =
                "Intensité — " + (if (pr.name.isEmpty()) "Mode ${i + 1}" else frName(pr.name, i))
            intnRows[i].visibility = if (pr.intnLen == 0) View.GONE else View.VISIBLE
            if (!intnSeeks[i].isPressed) intnSeeks[i].progress = pr.intn.coerceIn(0, 255)
        }
        infoTv.text = buildString {
            append("Version de l'app : $appVersion\n")
            append("Nom : ${l.devName.ifEmpty { "—" }}\n")
            append("Batterie : ${if (l.battery >= 0) Math.round(l.battery).toString() + " %" else "—"}")
            append(if (l.charging in 0..1) " (en charge)\n" else if (l.charging == 2) " (branché, plein)\n" else " (sur batterie)\n")
            append("Puffs au total (Proxy) : ${if (l.puffs >= 0) l.puffs else "—"}\n")
            append("Temps de chauffe total (brut) : ${if (l.heatTotalRaw >= 0) l.heatTotalRaw else "—"}\n")
            append("Diagnostic : état ${l.state}, temps ${"%.1f".format(l.elap)} / ${"%.1f".format(l.tott)}\n\n")
            append("Journal de connexion :\n")
            for (e in l.log) append(e).append('\n')
            append("\nAppareils Bluetooth vus (${l.seen.size}) :\n")
            for ((a, d) in l.seen.entries.sortedByDescending { l.seenRssi[it.key] ?: -200 }.take(8)) append("$a  $d\n")
        }
    }
}
