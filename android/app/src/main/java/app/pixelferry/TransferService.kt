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
import app.pixelferry.core.IngestResult
import app.pixelferry.core.ItemState
import app.pixelferry.core.NotEnoughSpace
import app.pixelferry.core.Pairing
import app.pixelferry.core.ReceiverServer
import app.pixelferry.core.VerifyFailed
import app.pixelferry.sources.MediaSource
import app.pixelferry.sources.MtpSource
import app.pixelferry.sources.SourceEntry
import java.io.IOException

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
            ACTION_RUN -> pendingJob?.let { job -> pendingJob = null; launch { runJob(job) } }
            ACTION_DELETE -> pendingDelete?.let { src -> pendingDelete = null; launch { runDelete(src) } }
            ACTION_FINISH -> launch { finalWave() }
            ACTION_STOP -> { cancel = true }
            ACTION_RECV_ON -> startReceiver()
            ACTION_RECV_OFF -> { stopReceiver(); maybeStop() }
        }
        return START_NOT_STICKY
    }

    private fun launch(block: () -> Unit) {
        if (worker?.isAlive == true) return
        cancel = false
        acquireLocks()
        worker = Thread({
            try { block() } catch (e: Exception) {
                Hub.run.title = "Stopped"; Hub.run.detail = "Error: ${e.message}"; Hub.run.errors += e.toString()
            } finally {
                Hub.run.active = false
                releaseLocks(); updateNotif(force = true); maybeStop()
            }
        }, "pf-job").also { it.start() }
    }

    private fun maybeStop() {
        if (worker?.isAlive != true && receiver == null) { stopForeground(true); stopSelf() }
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixelferry:run").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() { wake?.let { if (it.isHeld) it.release() }; wake = null }

    private fun updateNotif(force: Boolean = false) {
        val now = System.currentTimeMillis()
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
        val src = job.source
        val entries = job.entries.sortedBy { it.dateMs }
        r.errors.clear()
        r.active = true; r.title = "Transferring"; r.detail = "From ${job.profile.owner}'s ${job.profile.label}"
        r.total = entries.size; r.done = 0; r.bytesTotal = entries.sumOf { it.approxSize }; r.bytesDone = 0
        r.duplicates = 0; r.failed = 0; r.skipped = 0; r.startedAt = System.currentTimeMillis()
        Hub.engine.cleanTemp()
        val already = Hub.ledger.keysFor(job.profile.deviceKey)

        for (e in entries) {
            if (cancel) break
            if (e.itemKey in already) { r.skipped++; r.done++; r.bytesDone += e.approxSize; continue }
            batteryGuard()
            if (!Hub.engine.hasRoomFor(e.approxSize) && !Hub.wave.awaitRoom(e.approxSize, { cancel })) break
            var attempt = 0
            while (!cancel) {
                try {
                    r.detail = "Copying ${e.name} (${fmt(e.approxSize)})"
                    updateNotif()
                    val res = Hub.engine.ingest(job.profile, e.itemKey, e.name, e.size, e.dateMs, null) { src.copyTo(e, it) }
                    if (res is IngestResult.Duplicate) r.duplicates++
                    Hub.wave.fileStored()
                    break
                } catch (ex: NotEnoughSpace) {
                    if (!Hub.wave.awaitRoom(e.approxSize, { cancel })) break
                } catch (ex: VerifyFailed) {
                    if (++attempt >= 3) { r.failed++; r.errors += "${e.name}: ${ex.message}"; break }
                } catch (ex: IOException) {
                    if (!src.isConnected()) {
                        r.title = "Paused: cable unplugged"
                        r.detail = "Plug it back in and tap Resume. Finished files are kept and won't be copied again."
                        Notifs.alert(this, r.title, r.detail)
                        return
                    }
                    if (++attempt >= 3) { r.failed++; r.errors += "${e.name}: ${ex.message}"; break }
                    Thread.sleep(2000)
                }
            }
            r.done++; r.bytesDone += e.approxSize
            updateNotif()
        }
        if (cancel) { r.title = "Stopped"; r.detail = "Tap Start again any time: finished files are skipped."; return }

        val summary = "${r.done - r.skipped - r.failed - r.duplicates} new, ${r.duplicates} duplicates skipped, " +
            "${r.skipped} already done, ${r.failed} failed."
        finalWave(summary, job.profile)
    }

    /** Make sure the last files are confirmed by Google so they become deletable at the source. */
    private fun finalWave(summary: String = "", profile: DeviceProfile? = null) {
        val r = Hub.run
        r.active = true
        r.title = "Finishing: waiting for Google Photos"
        r.detail = "All files copied. $summary Waiting for Google Photos to back up the last ones, then Free up space."
        updateNotif(force = true)
        val ok = Hub.wave.awaitAllBackedUp({ Hub.ledger.countState(ItemState.VERIFIED) }, { cancel })
        val deletable = profile?.let { p ->
            "${Hub.ledger.deletableFor(p.deviceKey).size} files are backed up by Google and can be deleted from ${p.owner}'s ${p.label}."
        } ?: ""
        r.title = if (ok) "Done" else "Copy done (Google backup still pending)"
        r.detail = "$summary $deletable"
        Notifs.alert(this, r.title, r.detail)
    }

    private fun batteryGuard() {
        while (!cancel) {
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level > 12 || charging) return
            Hub.run.title = "Paused: Pixel battery low ($level%)"
            Hub.run.detail = "Charge the Pixel. Continues automatically at 25%."
            updateNotif(force = true)
            while (!cancel) {
                Thread.sleep(60_000)
                val b2 = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
                val l2 = b2.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b2.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l2 >= 25 || b2.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) { Hub.run.title = "Transferring"; return }
            }
        }
    }

    // ---------------- delete from source (after double confirm in UI) ----------------

    private fun runDelete(src: MediaSource) {
        val r = Hub.run
        r.active = true; r.title = "Deleting from ${src.suggestedLabel}"; r.detail = "Reading device…"
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
        r.title = "Delete finished"
        r.detail = "Removed ${r.done - r.failed} files from ${src.suggestedLabel}" + if (r.failed > 0) " (${r.failed} could not be removed)." else "."
    }

    // ---------------- Wi-Fi receiver for computers ----------------

    private var waveThread: Thread? = null

    private fun startReceiver() {
        if (receiver != null) return
        val token = Pairing.newToken()
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
        })
        srv.start()
        receiver = srv
        pairingCode = Pairing.code(token, Pairing.certPin(TlsIdentity.certDer()))
        acquireLocks()
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pixelferry:recv").apply { acquire() }
        Hub.run.title = "Receiving from computer"; Hub.run.detail = "Waiting for the computer to connect"; Hub.run.done = 0; Hub.run.bytesDone = 0
        updateNotif(force = true)
    }

    private fun stopReceiver() {
        receiver?.stop(); receiver = null; pairingCode = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        if (worker?.isAlive != true) releaseLocks()
    }

    companion object {
        const val ACTION_RUN = "run"
        const val ACTION_STOP = "stop"
        const val ACTION_DELETE = "delete"
        const val ACTION_FINISH = "finish"
        const val ACTION_RECV_ON = "recv_on"
        const val ACTION_RECV_OFF = "recv_off"

        @Volatile var pendingJob: Job? = null
        @Volatile var pendingDelete: MediaSource? = null
        @Volatile var receiver: ReceiverServer? = null
        @Volatile var pairingCode: String? = null

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(action))
        }

        fun fmt(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> String.format("%.0f MB", b / (1L shl 20).toDouble())
            else -> String.format("%.0f KB", b / 1024.0)
        }
    }
}
