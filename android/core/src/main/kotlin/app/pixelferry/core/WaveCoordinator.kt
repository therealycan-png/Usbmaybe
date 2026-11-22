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
    /**
     * Start Google Photos' Free up space. The implementation must call
     * [BackupTracker.openFreeUpWindow] itself at the moment the free-up is really confirmed
     * (helper pressing the confirm button, or the user tapping "Free up now").
     * @return true if an automatic attempt was started; false = user was asked to do it.
     */
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

    private var goal: () -> Boolean = { true }

    @Synchronized fun beginWait(bytes: Long) {
        if (phase == Phase.WAITING) { neededBytes = maxOf(neededBytes, bytes); return }
        start(bytes) { engine.hasRoomFor(neededBytes) }
        notifier.waiting(bytes, engine.freeBytes(), 0)
    }

    /** Final wave: after the last file, get everything still on the Pixel confirmed by Google. */
    @Synchronized fun beginFinal(remainingOnPixel: () -> Int) {
        start(0) { remainingOnPixel() == 0 }
    }

    private fun start(bytes: Long, done: () -> Boolean) {
        phase = Phase.WAITING; neededBytes = bytes; goal = done
        waitStarted = clock(); attempts = 0; lastAttempt = 0; stuckReported = false
    }

    /** One step of the waiting loop. @return true when the goal is reached. */
    @Synchronized fun tick(): Boolean {
        if (phase == Phase.RUNNING) return true
        tracker.reconcile()
        if (goal()) {
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

    /** Blocking wait used by the USB job. @return true if room came back (false = cancelled). */
    fun awaitRoom(bytes: Long, cancelled: () -> Boolean, sleepMs: Long = 20_000): Boolean {
        beginWait(bytes)
        return loop(cancelled, sleepMs)
    }

    fun awaitAllBackedUp(remainingOnPixel: () -> Int, cancelled: () -> Boolean, sleepMs: Long = 20_000): Boolean {
        beginFinal(remainingOnPixel)
        return loop(cancelled, sleepMs)
    }

    private var waiters = 0

    private fun loop(cancelled: () -> Boolean, sleepMs: Long): Boolean {
        synchronized(this) { waiters++ }
        try {
            while (!cancelled()) {
                if (tick()) return true
                Thread.sleep(sleepMs)
            }
            return false
        } finally {
            synchronized(this) { if (--waiters == 0 && phase == Phase.WAITING) phase = Phase.RUNNING }
        }
    }
}
