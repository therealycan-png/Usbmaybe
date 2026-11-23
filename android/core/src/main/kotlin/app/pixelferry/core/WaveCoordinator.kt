package app.pixelferry.core

interface WaveNotifier {
    /** The Pixel is full: the transfer is paused until you free space. */
    fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int)
    fun stuck(reason: String)
    fun resumed()
}

/**
 * Manual waves. When the Pixel is full the transfer simply pauses. You free space yourself with
 * Google Photos' own "Free up space" (started from PixelFerry's button, which opens a free-up
 * window so the freed photos count as backed up), then tap Continue. Nothing runs in the
 * background: no watching notifications, no auto-tapping, no timers that open apps.
 */
class WaveCoordinator(
    private val engine: IngestEngine,
    private val tracker: BackupTracker,
    private val notifier: WaveNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A paused transfer gives up after this long (so the phone isn't kept busy for days). */
    val maxWaitMs: Long = 24 * 3600_000L,
) {
    enum class Phase { RUNNING, WAITING }

    @Volatile var phase = Phase.RUNNING; private set
    @Volatile var neededBytes = 0L; private set
    @Volatile var waveNumber = 1; private set
    /** The wait ended without room coming back. */
    @Volatile var gaveUp = false; private set
    /** You chose "Skip this file": the waiting file is left on the device and the transfer moves on. */
    @Volatile var skipped = false; private set
    @Volatile private var skipRequested = false

    fun skipCurrent() { if (phase == Phase.WAITING) skipRequested = true }
    private var waitStarted = 0L
    private var lastReconcile = 0L

    fun fileStored() {}

    @Synchronized fun beginWait(bytes: Long) {
        if (phase == Phase.WAITING) { neededBytes = maxOf(neededBytes, bytes); return }
        phase = Phase.WAITING; neededBytes = bytes; waitStarted = clock(); gaveUp = false; skipped = false; skipRequested = false
        notifier.waiting(bytes, engine.freeBytes(), 0)
    }

    /** One check. @return true when the wait is over (room is back, or it gave up). */
    @Synchronized fun tick(): Boolean {
        if (phase == Phase.RUNNING) return true
        val now = clock()
        // while you're freeing space through PixelFerry's button, credit what Google removes
        if (tracker.isWindowOpen() && now - lastReconcile >= 15_000) { lastReconcile = now; tracker.reconcile() }
        if (engine.hasRoomFor(neededBytes)) {
            phase = Phase.RUNNING; waveNumber++; notifier.resumed(); return true
        }
        if (now - waitStarted > maxWaitMs) {
            gaveUp = true; phase = Phase.RUNNING
            notifier.stuck("The Pixel stayed full. Free up space in Google Photos, then tap Resume.")
            return true
        }
        return false
    }

    /** Blocking wait used by the transfer. @return true if room came back (false = cancelled or gave up). */
    fun awaitRoom(bytes: Long, cancelled: () -> Boolean, sleepMs: Long = 5_000): Boolean {
        beginWait(bytes)
        synchronized(this) { waiters++ }
        try {
            while (!cancelled()) {
                if (skipRequested) { skipRequested = false; skipped = true; synchronized(this) { phase = Phase.RUNNING }; notifier.resumed(); return false }
                if (tick()) return !gaveUp
                Thread.sleep(sleepMs)
            }
            return false
        } finally {
            synchronized(this) { if (--waiters == 0 && phase == Phase.WAITING) phase = Phase.RUNNING }
        }
    }

    private var waiters = 0
}
