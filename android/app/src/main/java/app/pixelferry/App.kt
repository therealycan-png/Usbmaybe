package app.pixelferry

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.pixelferry.core.BackupTracker
import app.pixelferry.core.IngestEngine
import app.pixelferry.core.WaveCoordinator
import app.pixelferry.sources.MediaSource

object GooglePhotos {
    const val PKG = "com.google.android.apps.photos"
    fun open(ctx: android.content.Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PKG)
        ?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Transfer progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Needs you", NotificationManager.IMPORTANCE_HIGH))
        // a device pulled out while PixelFerry is idle must not stay "connected" in the app
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                @Suppress("DEPRECATION") val d = i.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE) ?: return
                val s = Hub.source
                if (s is app.pixelferry.sources.MtpSource && s.isDevice(d)) { s.close(); if (!Hub.run.active) Hub.source = null }
            }
        }, android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    companion object {
        const val CH_RUN = "run"
        const val CH_ALERT = "alert"
        lateinit var instance: App; private set
    }
}

/** Single place that wires the shared engine together. */
object Hub {
    val ledger by lazy { SqliteLedger(App.instance) }
    val storage by lazy { AndroidStorage(App.instance) }
    val engine by lazy { IngestEngine(storage, ledger).also { e -> e.onStored = { bytes, at -> tracker.noteStored(bytes, at) }; e.onWarning = { run.errors += it } } }
    val tracker by lazy {
        val prefs = App.instance.getSharedPreferences("pf", 0)
        BackupTracker(ledger,
            onWindowChanged = { prefs.edit().putLong("windowUntil", it).apply() },
            storageReadable = { storage.isReadable() },
            refreshElsewhere = { storage.refreshElsewhere() },
            foundElsewhere = { name, size -> storage.existsElsewhere(name, size) },
            freeBytes = { storage.freeBytes() })
    }
    val wave by lazy { WaveCoordinator(engine, tracker, Notifs) }

    /**
     * Manual free-up: you tap "Free up space" in PixelFerry (opens a window for up to 2 h, then Google Photos);
     * in Google Photos you run its own Free up space; back in PixelFerry you tap Continue / Check.
     * Only photos Google removed during that window count as backed up.
     */
    fun startFreeUp(ctx: android.content.Context) {
        Thread { tracker.openFreeUpWindow(2 * 3600_000L) }.start()
        ctx.getSharedPreferences("pf", 0).edit().putLong("freeUpStarted", System.currentTimeMillis()).apply()
        GooglePhotos.open(ctx)
    }

    /** "Continue"/"Check": credit what Google freed, close the window. @return files now confirmed backed up. */
    fun finishFreeUp(ctx: android.content.Context): Int {
        val r = tracker.reconcile()
        tracker.closeWindow()
        ctx.getSharedPreferences("pf", 0).edit().remove("freeUpStarted").apply()
        return r.backedUp
    }

    /** Copies that left the Pixel without the Free up space button (e.g. you freed space in Google Photos first). */
    fun unclaimedGone(): List<app.pixelferry.core.Item> {
        tracker.reconcile() // anything newly gone is classified first
        return ledger.byState(app.pixelferry.core.ItemState.MISSING_UNKNOWN).filter { it.pixelPath != null && !java.io.File(it.pixelPath).exists() }
    }

    /** You confirmed (twice) that Google Photos' own Free up space removed them: count them as backed up. */
    fun claimFreedByGoogle(items: List<app.pixelferry.core.Item>) {
        ledger.setStates(items.associate { it.id to app.pixelferry.core.ItemState.BACKED_UP })
    }

    /** Copies still on the Pixel (Google hasn't freed them). */
    fun stillOnPixel(): List<app.pixelferry.core.Item> =
        ledger.byState(app.pixelferry.core.ItemState.VERIFIED).filter { it.pixelPath != null && java.io.File(it.pixelPath).exists() }

    /**
     * Override, after your spot check + double confirm: you checked they're in Google Photos, so PixelFerry
     * removes its own copies from the Pixel (frees space) and counts them as backed up.
     * @return how many were removed.
     */
    fun overrideBackedUp(items: List<app.pixelferry.core.Item>, progress: (Int) -> Unit = {}): Int {
        var n = 0
        val done = HashMap<Long, app.pixelferry.core.ItemState>()
        for (it in items) {
            if (storage.removeLocal(it.pixelPath!!)) { done[it.id] = app.pixelferry.core.ItemState.BACKED_UP; n++ }
            if (n % 100 == 0) { ledger.setStates(done); done.clear(); progress(n) }
        }
        ledger.setStates(done)
        return n
    }

    fun freeUpInProgress(ctx: android.content.Context): Boolean =
        tracker.isWindowOpen() && ctx.getSharedPreferences("pf", 0).getLong("freeUpStarted", 0) > 0

    /** The source the user currently has open (USB device or folder). */
    @Volatile var source: MediaSource? = null

    /** PixelFerry is on screen right now (only then may the helper open Google Photos by itself). */
    @Volatile var uiVisible = false

    val run = RunState()

    /** Check for freed files every 15 s while a free-up window is open, even when no wave is waiting. */
    fun watchWindow() {
        Thread {
            while (tracker.isWindowOpen()) { try { tracker.reconcile() } catch (_: Exception) {}; Thread.sleep(15_000) }
            try { tracker.reconcile() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }
}

/** Live status shown by the UI. */
/** What the home screen shows. Set explicitly, never guessed from text. */
enum class Phase { IDLE, COPYING, WAITING_GOOGLE, FINISHING, DONE, DONE_PENDING_GOOGLE, STOPPED, UNPLUGGED, LOW_BATTERY, DELETING, DELETE_DONE, RECEIVING, ERROR }

class RunState {
    @Volatile var phase = Phase.IDLE
    /** Device the last run was from, e.g. "Mom's iPhone13". */
    @Volatile var deviceLabel = ""
    /** File-name tag of the last run, e.g. "Mom_iPhone13" (to find them in Google Photos). */
    @Volatile var tag = ""
    /** When the current wait for Google began (0 = not waiting). */
    @Volatile var waitStartedAt = 0L
    @Volatile var active = false
    @Volatile var title = "Idle"
    @Volatile var detail = ""
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var bytesDone = 0L
    @Volatile var bytesTotal = 0L
    @Volatile var duplicates = 0
    @Volatile var failed = 0
    @Volatile var skipped = 0
    @Volatile var startedAt = 0L
    /** Bytes actually copied (not skipped) in this run: drives speed and time left. */
    @Volatile var bytesCopied = 0L
    /** When data last arrived from the device (to spot a real stall). */
    @Volatile var lastByteAt = 0L
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
}
