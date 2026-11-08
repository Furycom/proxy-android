package io.github.furycom.proxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.UUID

/** Constantes du protocole Lorax (Puffco Proxy). */
object P {
    val SVC: UUID = UUID.fromString("e276967f-ea8a-478a-a92e-d78f5dd15dd5")
    val CMD: UUID = UUID.fromString("60133d5c-5727-4f2c-9697-d842c5292a3c")
    val REPLY: UUID = UUID.fromString("8dc5ec05-8f7d-45ad-99db-3fbde65dbd9c")
    val VERSION: UUID = UUID.fromString("05434bca-cc7f-4ef6-bbb3-b1c520b9800c")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val KEY: ByteArray = ByteArray(16) { i ->
        "64c6456256f26f5b1ca12737a5dd71fb".substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    const val OP_SEED = 0x00
    const val OP_UNLOCK = 0x01
    const val OP_READ = 0x10
    const val OP_WRITE = 0x11

    // commandes envoyées à /p/app/mc
    const val MC_START = 7
    const val MC_STOP = 8
    const val MC_BOOST = 9
    const val MC_POWER_OFF = 0
    const val MC_BATT = 5

    fun u8(b: ByteArray, o: Int = 0): Int = if (b.size > o) b[o].toInt() and 255 else 0

    fun i32(b: ByteArray, o: Int = 0): Long {
        if (b.size < o + 4) return 0
        val v = (b[o].toLong() and 255) or ((b[o + 1].toLong() and 255) shl 8) or
            ((b[o + 2].toLong() and 255) shl 16) or ((b[o + 3].toLong() and 255) shl 24)
        return v.toInt().toLong() // signé
    }

    fun u32(b: ByteArray, o: Int = 0): Long = i32(b, o) and 0xFFFFFFFFL

    fun le32(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )

    /** Température en dixièmes de °C (Proxy) ou float °C. Renvoie des dixièmes de °C. */
    fun tempC10(b: ByteArray): Int {
        if (b.size < 4) return 0
        val i = i32(b).toInt()
        if (i in 50..4500) return i
        val f = java.lang.Float.intBitsToFloat(i)
        if (!f.isNaN() && f in 5f..450f) return (f * 10f).toInt()
        return if (i in 5..450) i * 10 else 0
    }

    fun c10ToF(c10: Int): Int = Math.round(c10 / 10.0 * 1.8 + 32.0).toInt()
    fun deltaC10ToF(c10: Int): Int = Math.round(c10 / 10.0 * 1.8).toInt()

    /** Batterie en pourcentage (le Proxy donne des centièmes de %). */
    fun battery(b: ByteArray): Double {
        if (b.isEmpty()) return -1.0
        if (b.size == 1) return u8(b).toDouble()
        val v = u32(b)
        return when {
            v <= 100 -> v.toDouble()
            v <= 10000 -> v / 100.0
            else -> -1.0
        }
    }

    /** Durées de session : flottant ou entier selon la taille. */
    fun numOrFloat(b: ByteArray): Double {
        if (b.size < 4) return 0.0
        val i = u32(b)
        val f = java.lang.Float.intBitsToFloat(i.toInt())
        return if (!f.isNaN() && f > 1e-3f && f < 1e7f) f.toDouble() else i.toDouble()
    }

    /** Écart de température du boost en dixièmes de °C (entier, ou float32 en °C). */
    fun deltaC10(b: ByteArray): Int {
        if (b.size < 4) return 0
        val i = i32(b).toInt()
        if (i in -1000..1000) return i
        val f = java.lang.Float.intBitsToFloat(i)
        return if (!f.isNaN() && f > -200f && f < 200f) Math.round(f * 10f) else 0
    }

    /** Durée ajoutée par le boost en centièmes de seconde. */
    fun deltaCs(b: ByteArray): Long {
        if (b.size < 4) return 0L
        val i = u32(b)
        if (i <= 100000L) return i
        val f = java.lang.Float.intBitsToFloat(i.toInt())
        return if (!f.isNaN() && f > 0f && f < 1000f) Math.round(f * 100f).toLong() else 0L
    }

    fun hex(b: ByteArray): String = b.take(48).joinToString("") { "%02x".format(it) } + (if (b.size > 48) "…" else "")

    fun str(b: ByteArray): String = String(b, Charsets.UTF_8).trimEnd('\u0000').trim()
}

class Profile {
    var name = ""
    var tempC10 = 0
    var intnLen = 0
    var intn = 0
    var boostC10 = 0
    var boostTimeRaw = 0L
}

/** Tout ce que l'app sait du Proxy en ce moment. */
class Live {
    var status = "Démarrage…"
    var ready = false
    var state = -1
    var tempC10 = 0
    var battery = -1.0
    var charging = -1
    var puffs = -1L
    var trip = -1L
    var heatTotalRaw = -1L
    var active = 0
    val profiles = Array(4) { Profile() }
    val boostC10: Int get() = profiles[active].boostC10
    val boostTimeRaw: Long get() = profiles[active].boostTimeRaw
    var stealth = false
    var lbrt = IntArray(4)
    var lantern = false
    var devName = ""
    var elap = 0.0
    var tott = 0.0
    val log = ArrayList<String>()
    val seen = LinkedHashMap<String, String>()
    val seenRssi = HashMap<String, Int>()

    val heating: Boolean get() = state in 7..9
}

@SuppressLint("MissingPermission")
object Hub {
    private var link: PuffcoLink? = null
    fun get(ctx: Context): PuffcoLink {
        var l = link
        if (l == null) {
            l = PuffcoLink(ctx.applicationContext) { }
            link = l
        }
        return l
    }
}

@SuppressLint("MissingPermission")
class PuffcoLink(private val ctx: Context, var onChange: () -> Unit) {
    val live = Live()

    private class Req(val op: Int, val payload: ByteArray, val cb: ((Int, ByteArray) -> Unit)?)

    private val h = Handler(Looper.getMainLooper())
    private val btMgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val prefs = ctx.getSharedPreferences("puffco", 0)

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var running = false
    private var scanning = false
    private var scanFails = 0
    private var linkReady = false
    private val queue = ArrayDeque<Req>()
    private var inflight: Req? = null
    private var seq = 0
    private var curSeq = -1
    private var tickCount = 0

    /** App invisible (écran éteint ou autre app devant) : on lit le Proxy moins souvent. */
    private var bg = true
    /** Dernier Proxy vu pendant une recherche (sert à la reconnexion automatique sans recherche). */
    private var lastDev: BluetoothDevice? = null
    private var scanName = ""
    /** Connexion automatique en attente (le système se branche dès que le Proxy s'allume). */
    private var autoWaiting = false
    private var bondAddr: String? = null
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CANADA_FRENCH)

    private fun now(): String = clock.format(java.util.Date())

    private fun set(msg: String) {
        if (live.status != msg && !msg.startsWith("Recherche du Proxy… (")) {
            val t = now()
            live.log.add(0, "$t  $msg")
            while (live.log.size > 14) live.log.removeAt(live.log.size - 1)
        }
        live.status = msg
        onChange()
    }

    // ---------------------------------------------------------------- cycle de vie

    fun start() {
        if (running) return
        running = true
        scanFails = 0
        bondTried = false
        try {
            ctx.registerReceiver(bondRx, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        } catch (_: Exception) {
        }
        beginScan()
    }

    fun stop() {
        running = false
        try {
            ctx.unregisterReceiver(bondRx)
        } catch (_: Exception) {
        }
        h.removeCallbacksAndMessages(null)
        stopScan()
        teardown("Déconnecté")
    }

    /** Appelé par l'écran : visible (false) ou non (true). */
    fun setBackground(b: Boolean) {
        if (bg == b) return
        bg = b
        if (!running || b) return
        // retour à l'écran : lecture rapide tout de suite
        if (live.ready) {
            h.removeCallbacks(tick)
            h.post(tick)
            return
        }
        // pas connecté : on reprend la recherche rapide habituelle
        scanFails = 0
        if (autoWaiting) {
            cancelAuto()
            beginScan()
        } else if (gatt == null) {
            if (scanning && scanLow) stopScan()
            if (!scanning) {
                h.removeCallbacks(retryRun)
                beginScan()
            }
        }
    }

    private val retryRun = Runnable { if (running) beginScan() }

    private fun retryLater(ms: Long = 3000) {
        if (!running) return
        h.removeCallbacks(retryRun)
        h.postDelayed(retryRun, ms)
    }

    // ---------------------------------------------------------------- recherche

    private fun locationOn(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isLocationEnabled
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post { onFound(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post {
                scanning = false
                set("Recherche impossible (code $errorCode)")
                retryLater(4000)
            }
        }
    }

    private fun beginScan() {
        if (!running || scanning || gatt != null) return
        val ad: BluetoothAdapter? = btMgr.adapter
        if (ad == null || !ad.isEnabled) {
            set("Active le Bluetooth du téléphone")
            retryLater()
            return
        }
        // app invisible et Proxy introuvable : plus de recherche, le système se rebranche seul dès qu'il s'allume
        val ld = lastDev
        if (bg && ld != null && scanFails >= 3) {
            autoConnect(ld)
            return
        }
        if (!locationOn()) {
            set("Active la localisation (GPS) du téléphone")
            retryLater()
            return
        }
        val sc = ad.bluetoothLeScanner
        if (sc == null) {
            retryLater()
            return
        }
        scanning = true
        scanLow = bg && scanFails >= 3
        set("Recherche du Proxy… (allume-le si besoin)")
        try {
            val mode = if (scanLow) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
            sc.startScan(null, ScanSettings.Builder().setScanMode(mode).build(), scanCb)
        } catch (e: Exception) {
            scanning = false
            set("Recherche impossible : ${e.message}")
            retryLater(4000)
            return
        }
        h.removeCallbacks(scanTimeout)
        h.postDelayed(scanTimeout, 10000)
    }

    private var scanLow = false

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            scanFails++
            // app invisible, aucun Proxy connu dans cette session : recherche espacée pour la batterie
            retryLater(if (bg && scanFails >= 3 && lastDev == null) 30000 else 500)
        }
    }

    private fun stopScan() {
        h.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            btMgr.adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
    }

    private fun onFound(r: ScanResult) {
        if (!scanning || gatt != null) return
        val addr = r.device.address
        val saved = prefs.getString("addr", null)
        val name = r.scanRecord?.deviceName ?: ""
        val hasSvc = r.scanRecord?.serviceUuids?.any { it.uuid == P.SVC } == true
        // un Proxy déjà connu : on ne se branche qu'à lui (jamais au Proxy d'un voisin) ;
        // sinon, premier branchement : un appareil qui annonce le service ou le nom du Proxy
        val match = if (saved != null) addr == saved
            else hasSvc || name.contains("proxy", true) || name.contains("puffco", true)
        val firstSeen = !live.seen.containsKey(addr)
        live.seen[addr] = (if (name.isEmpty()) "(sans nom)" else name) + (if (hasSvc) " [Lorax]" else "") +
            " " + r.rssi + " dBm"
        live.seenRssi[addr] = r.rssi
        if (firstSeen) set("Recherche du Proxy… (${live.seen.size} appareils vus)")
        else onChange()
        if (match) {
            stopScan()
            lastDev = r.device
            if (name.isNotEmpty()) scanName = name
            h.postDelayed({ if (running && gatt == null) connect(r.device) }, 300)
        }
    }

    // ---------------------------------------------------------------- connexion

    private val connectTimeout = Runnable {
        if (running && !live.ready && gatt != null && !autoWaiting) {
            teardown("Connexion trop longue")
            retryLater(1500)
        }
    }

    private fun connect(dev: BluetoothDevice) {
        set("Connexion au Proxy…")
        val g = try {
            dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            null
        }
        if (g == null) {
            failLink("Connexion impossible")
            return
        }
        gatt = g
        h.removeCallbacks(connectTimeout)
        h.postDelayed(connectTimeout, 20000)
    }

    /** Reconnexion sans recherche : le système se branche au Proxy dès qu'il réapparaît. */
    private fun autoConnect(dev: BluetoothDevice) {
        set("Recherche du Proxy… (allume-le si besoin)")
        val g = try {
            dev.connectGatt(ctx, true, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            null
        }
        if (g == null) {
            scanFails = 0
            retryLater()
            return
        }
        gatt = g
        autoWaiting = true
        h.removeCallbacks(autoWatchdog)
        h.postDelayed(autoWatchdog, 300000)
    }

    /** Filet de sécurité : si l'attente automatique ne donne rien en 5 min, une recherche normale. */
    private val autoWatchdog = Runnable {
        if (running && autoWaiting && !live.ready) {
            cancelAuto()
            scanFails = 0
            beginScan()
        }
    }

    private fun cancelAuto() {
        h.removeCallbacks(autoWatchdog)
        if (!autoWaiting) return
        autoWaiting = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun teardown(msg: String) {
        h.removeCallbacks(tick)
        h.removeCallbacks(connectTimeout)
        h.removeCallbacks(autoWatchdog)
        autoWaiting = false
        linkReady = false
        live.ready = false
        queue.clear()
        inflight = null
        cmdChar = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        live.state = -1
        set(msg)
    }

    private fun failLink(msg: String) {
        teardown(msg)
        retryLater(2500)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    scanFails = 0
                    if (autoWaiting) {
                        // la connexion automatique vient d'aboutir : même délai de préparation qu'une connexion normale
                        autoWaiting = false
                        h.removeCallbacks(autoWatchdog)
                        h.removeCallbacks(connectTimeout)
                        h.postDelayed(connectTimeout, 20000)
                    }
                    set("Connecté, préparation…")
                    h.postDelayed({
                        if (g === gatt && !g.requestMtu(247)) g.discoverServices()
                    }, 300)
                } else {
                    // une attente automatique qui échoue : on repasse par des recherches normales
                    if (autoWaiting) scanFails = 0
                    failLink(if (live.ready) "Connexion perdue, je reconnecte…" else "Connexion échouée, nouvel essai…")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            h.post { if (g === gatt) g.discoverServices() }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                if (g !== gatt) return@post
                val svc = g.getService(P.SVC)
                val cmd = svc?.getCharacteristic(P.CMD)
                val rep = svc?.getCharacteristic(P.REPLY)
                if (svc == null || cmd == null || rep == null) {
                    failLink("Ce n'est pas un Proxy (service introuvable)")
                    return@post
                }
                cmdChar = cmd
                g.setCharacteristicNotification(rep, true)
                val d = rep.getDescriptor(P.CCCD)
                if (d == null) {
                    failLink("Canal de réponse introuvable")
                    return@post
                }
                d.value = if (rep.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (!g.writeDescriptor(d)) failLink("Impossible d'écouter le Proxy")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post {
                if (g !== gatt) return@post
                log("Écoute des réponses : code $status")
                if (status == 5 || status == 15) { needBond("écoute refusée"); return@post }
                val ver = g.getService(P.SVC)?.getCharacteristic(P.VERSION)
                if (ver != null && g.readCharacteristic(ver)) log("Lecture de la version…") else handshake()
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            h.post {
                if (g !== gatt) return@post
                log("Version lue : code $status, ${ch.value?.size ?: 0} octets")
                if (status == 5 || status == 15) { needBond("lecture refusée"); return@post }
                handshake()
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != P.REPLY) return
            val v = ch.value?.copyOf() ?: return
            h.post { onReply(v) }
        }
    }

    // ---------------------------------------------------------------- protocole

    private val timeoutRun = Runnable { if (inflight != null) finish(-1, ByteArray(0)) }

    fun req(op: Int, payload: ByteArray = ByteArray(0), cb: ((Int, ByteArray) -> Unit)? = null) {
        queue.addLast(Req(op, payload, cb))
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (inflight != null || !linkReady) return
        val r = queue.removeFirstOrNull() ?: return
        val ch = cmdChar ?: return
        inflight = r
        seq = (seq + 1) and 0xFFFF
        if (seq == 0) seq = 1
        curSeq = seq
        val pkt = ByteArray(3 + r.payload.size)
        pkt[0] = seq.toByte()
        pkt[1] = (seq shr 8).toByte()
        pkt[2] = r.op.toByte()
        System.arraycopy(r.payload, 0, pkt, 3, r.payload.size)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = pkt
        val ok = gatt?.writeCharacteristic(ch) ?: false
        if (!ok) {
            finish(-2, ByteArray(0))
            return
        }
        h.postDelayed(timeoutRun, 2000)
    }

    private fun onReply(v: ByteArray) {
        if (v.size < 3) return
        val s = (v[0].toInt() and 255) or ((v[1].toInt() and 255) shl 8)
        if (s != curSeq || inflight == null) return
        h.removeCallbacks(timeoutRun)
        finish(v[2].toInt() and 255, v.copyOfRange(3, v.size))
    }

    private fun finish(st: Int, data: ByteArray) {
        h.removeCallbacks(timeoutRun)
        val r = inflight
        inflight = null
        r?.cb?.invoke(st, data)
        pump()
    }

    private fun handshake() {
        linkReady = true
        set("Identification…")
        req(P.OP_SEED) { st, seed ->
            log("Graine : code $st, ${seed.size} octets")
            if (st == 0 && seed.size >= 16) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(P.KEY)
                md.update(seed, 0, 16)
                val token = md.digest().copyOf(16)
                req(P.OP_UNLOCK, token) { st2, _ ->
                    log("Déverrouillage : code $st2")
                    if (st2 == 0) onAuthed() else failLink("Le Proxy a refusé l'identification (code $st2)")
                }
            } else if (st == -1) {
                needBond("pas de réponse à la demande")
            } else {
                failLink("Le Proxy a refusé (code $st)")
            }
        }
    }

    private fun log(msg: String) {
        live.log.add(0, "${now()}  $msg")
        while (live.log.size > 18) live.log.removeAt(live.log.size - 1)
        onChange()
    }

    private var bondTried = false

    /** Le Proxy ne répond pas sans lien chiffré : on demande l'appairage au système, puis on se reconnecte. */
    private fun needBond(why: String) {
        val dev = gatt?.device
        log("Besoin de jumelage ($why), état ${dev?.bondState}")
        if (dev == null || bondTried || dev.bondState == BluetoothDevice.BOND_BONDED) {
            failLink("Le Proxy ne répond pas")
            return
        }
        bondTried = true
        bondAddr = dev.address
        set("Jumelage avec le Proxy… (accepte si le téléphone demande)")
        if (!dev.createBond()) failLink("Jumelage impossible")
    }

    private val bondRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val who = dev?.address
            h.post {
                // seulement le jumelage du Proxy (pas celui des écouteurs ou d'une montre)
                if (who == null || who != bondAddr) return@post
                log("Jumelage : état $st")
                if (st == BluetoothDevice.BOND_BONDED && bondTried) {
                    teardown("Jumelé, je me reconnecte…")
                    retryLater(800)
                } else if (st == BluetoothDevice.BOND_NONE && bondTried) {
                    failLink("Jumelage refusé")
                }
            }
        }
    }

    /** Nom Bluetooth annoncé par le Proxy (ex. « Proxy 12345 »), si le téléphone le connaît. */
    private fun advName(): String {
        val n = try {
            gatt?.device?.name
        } catch (_: Exception) {
            null
        }
        return if (!n.isNullOrBlank()) n.trim() else scanName
    }

    private fun onAuthed() {
        h.removeCallbacks(connectTimeout)
        live.ready = true
        // l'adresse n'est mémorisée qu'une fois l'identification réussie (jamais celle d'un autre appareil)
        gatt?.device?.address?.let { prefs.edit().putString("addr", it).apply() }
        if (live.devName.isEmpty()) live.devName = advName()
        set("Connecté")
        readAll()
        tickCount = 0
        h.postDelayed(tick, 800)
    }

    // ---------------------------------------------------------------- lecture / écriture

    fun read(path: String, cb: (ByteArray) -> Unit) {
        val pb = path.toByteArray()
        val p = ByteArray(4 + pb.size)
        p[2] = 240.toByte() // max_len = 240, little-endian
        System.arraycopy(pb, 0, p, 4, pb.size)
        req(P.OP_READ, p) { st, data ->
            if (st == 0) cb(data)
        }
    }

    fun write(path: String, value: ByteArray, cb: ((Boolean) -> Unit)? = null) {
        val pb = path.toByteArray()
        val p = ByteArray(3 + pb.size + 1 + value.size)
        System.arraycopy(pb, 0, p, 3, pb.size)
        System.arraycopy(value, 0, p, 3 + pb.size + 1, value.size)
        req(P.OP_WRITE, p) { st, _ ->
            cb?.invoke(st == 0)
        }
    }

    private fun readAll() {
        val l = live
        // nom donné par l'utilisateur ; sinon le nom Bluetooth annoncé
        read("/u/sys/name") { l.devName = P.str(it).ifEmpty { advName() }; onChange() }
        for (i in 0..3) {
            val pr = l.profiles[i]
            read("/u/app/hc/$i/name") { pr.name = P.str(it); onChange() }
            read("/u/app/hc/$i/temp") { pr.tempC10 = P.tempC10(it); onChange() }
            read("/u/app/hc/$i/btmp") { pr.boostC10 = P.deltaC10(it); onChange() }
            read("/u/app/hc/$i/btim") { pr.boostTimeRaw = P.deltaCs(it); onChange() }
            read("/u/app/hc/$i/intn") {
                pr.intnLen = it.size
                pr.intn = if (it.isEmpty()) 0 else if (it.size >= 4) P.u32(it).toInt() else P.u8(it)
                onChange()
            }
        }
        read("/p/app/hcs") { l.active = P.u8(it).coerceIn(0, 3); onChange() }
        read("/u/app/ui/stlm") { l.stealth = P.u8(it) != 0; onChange() }
        read("/u/app/ui/lbrt") { for (k in 0..3) l.lbrt[k] = P.u8(it, k); onChange() }
        read("/p/app/ltrn/cmd") { l.lantern = P.u8(it) != 0; onChange() }
        slowPoll()
    }

    private fun slowPoll() {
        val l = live
        read("/p/bat/soc") { l.battery = P.battery(it); onChange() }
        read("/p/bat/chg/stat") { l.charging = P.u8(it); onChange() }
        read("/p/app/odom/0/nc") { l.puffs = P.u32(it); onChange() }
        // lecture seule : l'app n'écrit dans le Proxy que sur un geste de l'utilisateur
        read("/p/app/odom/1/nc") { l.trip = P.u32(it); onChange() }
        read("/p/app/odom/0/tm") { l.heatTotalRaw = P.u32(it); onChange() }
        read("/p/app/hcs") { l.active = P.u8(it).coerceIn(0, 3); onChange() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!live.ready) return
            if (queue.size < 4) {
                val l = live
                read("/p/app/stat/id") { l.state = P.u8(it); onChange() }
                read("/p/app/htr/temp") { l.tempC10 = P.tempC10(it); onChange() }
                if (l.heating) {
                    read("/p/app/stat/elap") { l.elap = P.numOrFloat(it); onChange() }
                    read("/p/app/stat/tott") { l.tott = P.numOrFloat(it); onChange() }
                }
                tickCount++
                if (tickCount % 12 == 0) slowPoll()
            }
            // app invisible et Proxy au repos : lecture espacée (batterie) ; sinon le rythme habituel
            h.postDelayed(this, if (bg && !live.heating) 3000 else 700)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun mc(v: Int, cb: ((Boolean) -> Unit)? = null) = write("/p/app/mc", byteArrayOf(v.toByte()), cb)

    fun startSession() {
        live.elap = 0.0
        live.tott = 0.0
        mc(P.MC_START)
    }

    fun stopSession() = mc(P.MC_STOP)
    fun boost() = mc(P.MC_BOOST)
    fun powerOff() = mc(P.MC_POWER_OFF)
    fun showBattery() = mc(P.MC_BATT)

    /** Compteur de puffs depuis le dernier nettoyage complet : gardé dans le Proxy (odomètre de voyage). */
    fun setTrip(v: Long) {
        val x = v.coerceAtLeast(0)
        live.trip = x
        write("/p/app/odom/1/nc", P.le32(x.toInt()))
        onChange()
    }

    fun selectProfile(i: Int) {
        live.active = i
        write("/p/app/hcs", byteArrayOf(i.toByte()))
        onChange()
    }

    fun setBoostTemp(c10: Int) {
        val i = live.active
        live.profiles[i].boostC10 = c10
        write("/u/app/hc/$i/btmp", P.le32(c10))
        onChange()
    }

    fun setBoostTime(raw: Long) {
        val i = live.active
        live.profiles[i].boostTimeRaw = raw
        write("/u/app/hc/$i/btim", P.le32(raw.toInt()))
        onChange()
    }

    fun setStealth(on: Boolean) {
        live.stealth = on
        write("/u/app/ui/stlm", byteArrayOf(if (on) 1 else 0))
    }

    fun setLantern(on: Boolean) {
        live.lantern = on
        write("/p/app/ltrn/cmd", byteArrayOf(if (on) 1 else 0))
    }

    fun setBrightnessAll(v: Int) {
        val b = v.coerceIn(0, 255).toByte()
        for (k in 0..3) live.lbrt[k] = v
        write("/u/app/ui/lbrt", byteArrayOf(b, b, b, b))
    }

    fun setProfileIntensity(i: Int, v: Int) {
        val pr = live.profiles[i]
        if (pr.intnLen == 0) return
        pr.intn = v
        val out = ByteArray(pr.intnLen)
        for (k in 0 until pr.intnLen) out[k] = ((v shr (8 * k)) and 255).toByte()
        write("/u/app/hc/$i/intn", out)
    }
}






