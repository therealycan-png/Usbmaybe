package app.pixelferry.core

/** What the platform tells us about Google Photos. Android: NotificationListenerService. */
interface BackupSignals {
    /** Is Google Photos currently showing a "Backing up…" notification? null = no notification access. */
    fun backingUp(): Boolean?
    /** Last time Google Photos backup activity was seen (ms), 0 if never. */
    fun lastActivityMs(): Long
}

/** Android: AccessibilityService that taps Google Photos' own "Free up space". */
interface FreeUpAction {
    /** @return true if an automatic attempt was started; false = user must tap (we notified them). */
    fun requestFreeUp(auto: Boolean): Boolean
}

interface WaveNotifier {
    fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int)
    fun stuck(reason: String)
    fun resumed()
}

/**
 * Pixel full -> wait until Google Photos is done uploading -> run Free up space
 * (Google removes only what it has backed up) -> space returns -> keep going.
 * No guessing, no deleting on our own.
 */
class WaveCoordinator(
    private val engine: IngestEngine,
    private val tracker: BackupTracker,
    private val signals: BackupSignals,
    private val freeUp: FreeUpAction,
    private val notifier: WaveNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Google Photos must look idle this long before we free up. */
    val quietMs: Long = 4 * 60_000L,
    /** Minimum time between free-up attempts. */
    val retryMs: Long = 12 * 60_000L,
    /** Wait at least this long after the wave's last file so Photos notices it. */
    val settleMs: Long = 3 * 60_000L,
    private val autoTap: () -> Boolean = { true },
) {
    enum class Phase { RUNNING, WAITING }

    @Volatile var phase = Phase.RUNNING; private set
    @Volatile var neededBytes = 0L; private set
    @Volatile var waveNumber = 1; private set
    private var waitStarted = 0L
    private var lastAttempt = 0L
    private var attempts = 0
    private var lastFileAt = 0L
    private var stuckReported = false

    fun fileStored() { lastFileAt = clock() }

    fun beginWait(bytes: Long) {
        if (phase == Phase.WAITING) { neededBytes = maxOf(neededBytes, bytes); return }
        phase = Phase.WAITING; neededBytes = bytes
        waitStarted = clock(); attempts = 0; lastAttempt = 0; stuckReported = false
        notifier.waiting(bytes, engine.freeBytes(), 0)
    }

    /** One step of the waiting loop. @return true when there is room again. */
    fun tick(): Boolean {
        if (phase == Phase.RUNNING) return true
        tracker.reconcile()
        if (engine.hasRoomFor(neededBytes)) {
            phase = Phase.RUNNING; waveNumber++; notifier.resumed(); return true
        }
        val now = clock()
        if (now - lastFileAt < settleMs) return false
        val busy = signals.backingUp()
        val idleLongEnough = when (busy) {
            true -> false
            false -> now - signals.lastActivityMs() >= quietMs
            null -> now - waitStarted >= quietMs * 3 // no notification access: just wait longer
        }
        if (idleLongEnough && (lastAttempt == 0L || now - lastAttempt >= retryMs)) {
            attempts++; lastAttempt = now
            tracker.openFreeUpWindow()
            val auto = freeUp.requestFreeUp(autoTap())
            notifier.waiting(neededBytes, engine.freeBytes(), attempts)
            if (!auto) { /* user notified to tap Free up space; window is open for them */ }
        }
        if (attempts >= 4 && !stuckReported) {
            stuckReported = true
            notifier.stuck(
                "Free up space ran $attempts times but no room came back. In Google Photos > Settings > " +
                    "Backup > Back up device folders, turn ON every PixelFerry folder, and keep Wi-Fi on."
            )
        }
        return false
    }

    /** Blocking wait used by the USB job. */
    fun awaitRoom(bytes: Long, cancelled: () -> Boolean, sleepMs: Long = 20_000) {
        beginWait(bytes)
        while (!cancelled()) {
            if (tick()) return
            Thread.sleep(sleepMs)
        }
    }
}
