package app.pixelferry

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.pixelferry.core.BackupTracker
import app.pixelferry.core.IngestEngine
import app.pixelferry.core.WaveCoordinator
import app.pixelferry.sources.MediaSource

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Transfer progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Needs you", NotificationManager.IMPORTANCE_HIGH))
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
    val engine by lazy { IngestEngine(storage, ledger) }
    val tracker by lazy {
        val prefs = App.instance.getSharedPreferences("pf", 0)
        BackupTracker(ledger,
            onWindowChanged = { prefs.edit().putLong("windowUntil", it).apply() },
            storageReadable = { storage.isReadable() },
            foundElsewhere = { name -> storage.existsElsewhere(name) })
    }
    val wave by lazy { WaveCoordinator(engine, tracker, PhotosWatcher.Signals, FreeUpController, Notifs) }

    /** The source the user currently has open (USB device or folder). */
    @Volatile var source: MediaSource? = null

    val run = RunState()
}

/** Live status shown by the UI. */
class RunState {
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
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
}
