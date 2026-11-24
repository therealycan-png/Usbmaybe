package app.pixelferry

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import app.pixelferry.core.DeviceProfile
import app.pixelferry.core.ItemState
import app.pixelferry.core.LoopEnd
import app.pixelferry.core.Pairing
import app.pixelferry.core.ReceiverServer
import app.pixelferry.core.TransferLoop
import app.pixelferry.core.WaveCoordinator
import app.pixelferry.sources.MediaSource
import app.pixelferry.sources.MtpSource
import app.pixelferry.core.SourceEntry

/**
 * Runs transfers in the background (screen can be off), keeps the CPU awake,
 * and survives the app being swiped away. One job at a time.
 */
class TransferService : Service() {

    private var worker: Thread? = null
    @Volatile private var cancel = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotif = 0L

    private val detach = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION") val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val s = Hub.source
            if (s is MtpSource && s.isDevice(d)) s.close()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(detach, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        startForeground(Notifs.ID_RUN, Notifs.progress(this, "PixelFerry", "Ready", 0, 0))
    }

    override fun onDestroy() {
        unregisterReceiver(detach)
        stopReceiver()
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN, ACTION_DELETE -> runPendingOrInterruptFinish()
            ACTION_STOP -> { cancel = true }
            ACTION_RECV_ON -> { starting = true; stopRequested = false; lastComputerActivity = System.currentTimeMillis(); Thread { startReceiver() }.start() }
            ACTION_RECV_OFF -> { stopRequested = true; stopReceiver() }
        }
        main.post { maybeStop() }
        return START_NOT_STICKY
    }

    /**
     * A new transfer or a confirmed delete always gets done: if only the "waiting for Google" final step is
     * running, it is interrupted (it can be redone with Finish now); the request runs as soon as it stops.
     */
    private fun runPendingOrInterruptFinish() {
        if (worker?.isAlive == true) {
            return // picked up again when the worker ends
        }
        pendingDelete?.let { src -> pendingDelete = null; launch { runDelete(src) }; return }
        pendingJob?.let { job -> pendingJob = null; launch { runJob(job) } }
    }

    private fun launch(block: () -> Unit) {
        if (worker?.isAlive == true) return
        cancel = false
        val t = Thread({
            try { block() } catch (e: Exception) {
                Hub.run.phase = Phase.ERROR
                Hub.run.title = "Something went wrong"
                Hub.run.detail = friendly(e)
                Hub.run.errors += e.toString()
                Diagnostics.log(this, "ERROR", android.util.Log.getStackTraceString(e).take(4000))
            } finally {
                Hub.run.active = false
                updateNotif(force = true)
                main.post { worker = null; runPendingOrInterruptFinish(); syncLocks(); maybeStop() }
            }
        }, "pf-job")
        worker = t
        syncLocks()
        t.start()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var starting = false
    @Volatile private var stopRequested = false

    private fun busy() = worker?.isAlive == true || receiver != null || starting

    private fun maybeStop() {
        if (!busy()) { @Suppress("DEPRECATION") stopForeground(true); stopSelf() }
    }

    /** One wake lock, held exactly while a job or the receiver is active. */
    @Synchronized private fun syncLocks() {
        val w = wake ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixelferry:run").apply { setReferenceCounted(false) }.also { wake = it }
        // 12 h safety timeout (refreshed while busy), so a bug can never keep the Pixel awake forever
        if (busy()) { w.acquire(12 * 3600_000L); lastLockRefresh = System.currentTimeMillis() } else if (w.isHeld) w.release()
    }

    private fun releaseLocks() { wake?.let { if (it.isHeld) it.release() } }

    private var lastLockRefresh = 0L

    private fun updateNotif(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastLockRefresh > 10 * 60_000L) main.post { syncLocks() }
        if (!force && now - lastNotif < 1000) return
        lastNotif = now
        val r = Hub.run
        val text = "${r.done} / ${r.total} · ${fmt(r.bytesDone)} of ${fmt(r.bytesTotal)}\n${r.detail}"
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifs.ID_RUN, Notifs.progress(this, r.title, text, r.done, r.total))
    }

    // ---------------- USB / folder job ----------------

    class Job(val source: MediaSource, val profile: DeviceProfile, val entries: List<SourceEntry>)

    private fun runJob(job: Job) {
        val r = Hub.run
        r.errors.clear()
        r.deviceLabel = "${job.profile.owner}'s ${job.profile.label}"
        r.tag = app.pixelferry.core.Naming.tag(job.profile.owner, job.profile.label)
        r.phase = Phase.COPYING
        r.active = true; r.title = "Copying from ${r.deviceLabel}"; r.detail = "Getting started…"
        r.done = 0; r.bytesDone = 0; r.duplicates = 0; r.failed = 0; r.skipped = 0; r.startedAt = System.currentTimeMillis()
        val loop = TransferLoop(Hub.engine, Hub.wave, Hub.ledger, cancelled = { cancel }, beforeEach = { batteryGuard() },
            onUpdate = { p ->
                r.total = p.total; r.bytesTotal = p.bytesTotal; r.done = p.done; r.bytesDone = p.bytesDone + p.fileBytes; r.bytesCopied = p.copiedBytes + p.fileBytes; r.lastByteAt = p.lastByteAt
                r.duplicates = p.duplicates; r.skipped = p.skipped; r.failed = p.failed
                if (Hub.wave.phase == WaveCoordinator.Phase.RUNNING && r.phase != Phase.LOW_BATTERY) {
                    r.phase = Phase.COPYING; r.title = "Copying from ${r.deviceLabel}"
                    r.detail = if (p.fileSize > 50L * 1024 * 1024) "${p.current} · ${fmt(p.fileBytes)} of ${fmt(p.fileSize)}" else p.current
                }
                p.errors.lastOrNull()?.let { if (it !in r.errors) r.errors += it }
                updateNotif()
            })
        Diagnostics.log(this, "RUN", "start ${r.deviceLabel} key=${job.profile.deviceKey} files=${job.entries.size} bytes=${job.entries.sumOf { it.approxSize }} free=${Hub.storage.freeBytes()}")
        val end = try { loop.run(job.profile, job.source, job.entries) } finally {
            val pr = loop.progress
            Diagnostics.recordFailures(this, job.profile.deviceKey, r.deviceLabel, pr.failures.toList())
            Diagnostics.log(this, "RUN", "end stored=${pr.stored} dup=${pr.duplicates} skipped=${pr.skipped} failed=${pr.failed} copied=${pr.copiedBytes}")
            Diagnostics.snapshot(this)
        }
        Diagnostics.log(this, "RUN", "result $end")
        val p = loop.progress
        when (end) {
            LoopEnd.CANCELLED -> {
                r.phase = Phase.STOPPED; r.title = "Stopped"
                r.detail = "Everything copied so far is kept. Open the device again and tap Resume to carry on."; return
            }
            LoopEnd.UNPLUGGED -> {
                r.phase = Phase.UNPLUGGED
                r.title = "Cable unplugged"
                r.detail = "Plug ${r.deviceLabel} back in and unlock it, then tap Resume. Nothing is copied twice."
                Notifs.alert(this, r.title, r.detail); return
            }
            LoopEnd.ALL_FAILING -> {
                r.phase = Phase.ERROR
                r.title = "Stopped: the first files didn't copy"
                r.detail = "Nothing was lost or deleted. Tap Show details and send that to whoever is helping you."
                Notifs.alert(this, r.title, r.detail); return
            }
            LoopEnd.STUCK_FULL -> {
                r.phase = Phase.ERROR
                r.title = "Paused: Google isn't freeing space"
                r.detail = "The Pixel is full and Google Photos hasn't made room for hours. In Google Photos › profile picture › Photos settings › Backup, check backup is on and the PixelFerry folder is turned on, keep Wi-Fi on, then tap Resume."
                Notifs.stuck(""); return
            }
            LoopEnd.FINISHED -> {
                // everything picked was handled: no "Unfinished" card for this device any more
                java.io.File(filesDir, app.pixelferry.ui.PickActivity.selectionName(job.profile.deviceKey)).delete()
            }
        }
        val summary = listOfNotNull("%,d copied".format(p.stored),
            if (p.skipped > 0) "%,d already sent before".format(p.skipped) else null,
            if (p.duplicates > 0) "%,d exact copies skipped".format(p.duplicates) else null,
            if (p.failed > 0) "%,d left on the device".format(p.failed) else null).joinToString(" · ")
        r.phase = Phase.DONE
        r.title = "All copied 🎉"
        r.detail = "$summary\nWhen Google Photos has backed them up, tap \"Make them deletable\" to free space and allow deleting from ${r.deviceLabel.ifEmpty { "the device" }}."
        Notifs.alert(this, r.title, "$summary")
    }

    private fun batteryGuard() {
        while (!cancel) {
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level > 15 || charging) return
            Hub.run.phase = Phase.LOW_BATTERY
            Hub.run.title = "Paused: Pixel battery low ($level%)"
            Hub.run.detail = "Unplug the other device and charge the Pixel. It continues by itself once it's charging or back at 30%."
            updateNotif(force = true)
            Notifs.alert(this, "Pixel battery low: transfer paused", "Unplug the other device and charge the Pixel. Nothing is lost; it continues by itself.")
            while (!cancel) {
                Thread.sleep(5_000)
                val b2 = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
                val l2 = b2.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b2.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l2 >= 30 || b2.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) { Hub.run.phase = Phase.COPYING; return }
            }
        }
    }

    // ---------------- delete from source (after double confirm in UI) ----------------

    private fun runDelete(src: MediaSource) {
        val r = Hub.run
        val name = Hub.ledger.device(src.deviceKey)?.let { "${it.owner}'s ${it.label}" } ?: src.suggestedLabel
        r.phase = Phase.DELETING
        r.active = true; r.title = "Deleting from $name"; r.detail = "Checking the device…"
        if (!src.canDelete) { r.phase = Phase.STOPPED; r.title = "Not deleting"; r.detail = "This device has no serial number, so it can't be safely identified."; return }
        val ok = Hub.ledger.deletableFor(src.deviceKey).associateBy { it.itemKey }
        val entries = src.list { r.detail = "Reading device… $it" }.filter { it.itemKey in ok }
        r.total = entries.size; r.done = 0; r.bytesDone = 0; r.bytesTotal = entries.sumOf { it.approxSize }; r.failed = 0
        for (e in entries) {
            if (cancel || !src.isConnected()) break
            val item = ok.getValue(e.itemKey)
            if (!Hub.ledger.isSafeToDeleteFromSource(Hub.ledger.get(item.id) ?: continue)) continue
            if (src.delete(e)) Hub.ledger.setState(item.id, ItemState.SOURCE_DELETED) else r.failed++
            r.done++; r.bytesDone += e.approxSize
            r.detail = "Deleted ${r.done} of ${r.total}"; updateNotif()
        }
        r.phase = Phase.DELETE_DONE
        r.title = "Space freed on $name 🎉"
        r.detail = "Removed ${r.done - r.failed} files from ${src.suggestedLabel}" + if (r.failed > 0) " (${r.failed} could not be removed)." else "."
    }

    // ---------------- Wi-Fi receiver for computers ----------------

    private var waveThread: Thread? = null
    @Volatile private var lastComputerActivity = 0L
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun startReceiver() {
        try {
            startReceiverInner()
            if (stopRequested) stopReceiver() // switched off while it was starting
        } catch (e: Exception) {
            receiver?.stop(); receiver = null; pairingCode = null
        discovery?.stop(); discovery = null
        multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
        if (Hub.run.phase == Phase.RECEIVING) { Hub.run.phase = Phase.IDLE; Hub.run.total = 0 }
            Hub.run.phase = Phase.ERROR
            Hub.run.title = "Couldn't get ready for the computer"; Hub.run.detail = "Check the Pixel is on Wi-Fi, then tap 'A computer' again."
            main.post { syncLocks(); maybeStop() }
        } finally {
            starting = false
            main.post { syncLocks(); maybeStop() }
        }
    }

    @Synchronized private fun startReceiverInner() {
        if (receiver != null) return
        val token = sessionToken(this)
        val srv = ReceiverServer(Hub.engine, Hub.ledger, TlsIdentity.sslContext(), token, object : ReceiverServer.Listener {
            override fun onStored(name: String, size: Long) {
                Hub.wave.fileStored()
                Hub.run.done++; Hub.run.bytesDone += size; Hub.run.detail = "Received $name"; updateNotif()
            }
            override fun onNeedSpace(bytes: Long) {
                if (waveThread?.isAlive == true) return
                waveThread = Thread { Hub.wave.awaitRoom(bytes, { receiver == null }) }.also { it.isDaemon = true; it.start() }
            }
            override fun isWaitingForBackup() = Hub.wave.phase == app.pixelferry.core.WaveCoordinator.Phase.WAITING
            override fun statusMessage() = "${Hub.run.title}. ${Hub.run.detail}"
            override fun log(msg: String) { Hub.run.errors += msg }
            override fun onActivity() { lastComputerActivity = System.currentTimeMillis() }
        }, trustedTokens = { computerKeys(this) }, onPaired = { k -> addComputerKey(this, k) })
        srv.start()
        receiver = srv
        @Suppress("DEPRECATION")
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("pixelferry:discovery").apply { setReferenceCounted(false); acquire() } // let broadcast "where's the Pixel?" packets in
        discovery = app.pixelferry.core.Discovery({ srv.localPort }, Pairing.certPin(TlsIdentity.certDer())).also {
            try { it.start() } catch (_: Exception) { /* the address can still be typed */ }
        }
        pairingCode = Pairing.code(token, Pairing.certPin(TlsIdentity.certDer()))
        main.post { syncLocks() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pixelferry:recv").apply { acquire() }
        Hub.run.phase = Phase.RECEIVING
        Hub.run.title = "Ready for your computer"; Hub.run.detail = "Waiting for the computer to connect"
        Hub.run.done = 0; Hub.run.bytesDone = 0; Hub.run.total = 0; Hub.run.bytesTotal = 0; Hub.run.bytesCopied = 0
        lastComputerActivity = System.currentTimeMillis()
        // switch off by itself after 15 idle minutes (Wi-Fi lock + discovery drain the battery)
        Thread {
            while (receiver != null) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                if (receiver != null && System.currentTimeMillis() - lastComputerActivity > 15 * 60_000L && Hub.wave.phase != app.pixelferry.core.WaveCoordinator.Phase.WAITING) {
                    main.post { stopReceiver(); maybeStop() }; break
                }
            }
        }.apply { isDaemon = true; start() }
        updateNotif(force = true)
    }

    @Synchronized private fun stopReceiver() {
        receiver?.stop(); receiver = null; pairingCode = null
        discovery?.stop(); discovery = null
        multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
        if (Hub.run.phase == Phase.RECEIVING) { Hub.run.phase = Phase.IDLE; Hub.run.total = 0 }
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        syncLocks()
    }

    companion object {
        const val ACTION_RUN = "run"
        const val ACTION_STOP = "stop"
        const val ACTION_DELETE = "delete"
        const val ACTION_RECV_ON = "recv_on"
        const val ACTION_RECV_OFF = "recv_off"

        @Volatile var pendingJob: Job? = null
        @Volatile var pendingDelete: MediaSource? = null
        @Volatile var receiver: ReceiverServer? = null
        @Volatile var discovery: app.pixelferry.core.Discovery? = null

        /** At most 20 remembered computers (oldest dropped), updated atomically. */
        @Synchronized fun addComputerKey(ctx: Context, k: String) {
            val p = ctx.getSharedPreferences("pf", 0)
            val list = (p.getString("computerKeyList", "") ?: "").split(",").filter { it.isNotBlank() } + k
            val keep = list.takeLast(20)
            p.edit().putString("computerKeyList", keep.joinToString(",")).putStringSet("computerKeys", keep.toSet()).commit()
        }

        fun computerKeys(ctx: Context): Set<String> = ctx.getSharedPreferences("pf", 0).getStringSet("computerKeys", emptySet())!!.toSet()
        /** Forget every paired computer AND make a new code, so old codes/bundles stop working. */
        fun forgetComputers(ctx: Context) = ctx.getSharedPreferences("pf", 0).edit().remove("computerKeys").remove("computerKeyList").remove("sessionToken").apply()

        /** The code's secret part stays the same until "Forget paired computers" (so a shared sender keeps working). */
        fun sessionToken(ctx: Context): String {
            val p = ctx.getSharedPreferences("pf", 0)
            return p.getString("sessionToken", null) ?: Pairing.newToken().also { p.edit().putString("sessionToken", it).apply() }
        }
        @Volatile var pairingCode: String? = null

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(action))
        }

        fun fmt(b: Long): String = when {
            b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
            else -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        }
    }
}

/** Plain-language version of an error for the screen (details stay in the log). */
fun friendly(e: Throwable): String {
    val m = (e.message ?: "").lowercase()
    return when {
        "locked" in m || "trust" in m || "file transfer" in m -> e.message!!
        "unplug" in m || "stopped sending" in m || "disconnect" in m -> "Lost the connection to the device. Plug it in again, unlock it, and tap Resume."
        "permission" in m || "cannot create" in m -> "PixelFerry can't save on this Pixel. Open Setup and allow it."
        "space" in m || "full" in m -> "The Pixel is full. Free up space in Google Photos and try again."
        else -> "Something unexpected happened. Your photos are safe. Tap Resume to try again."
    }
}
