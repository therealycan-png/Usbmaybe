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
    /** Time between free-up attempts; grows (×1, ×1.7, ×3.3, ×5) so it never nags. */
    val retryMs: Long = 12 * 60_000L,
    /** Final wave: stop after this many attempts in a row that freed nothing. */
    val finalMaxFruitless: Int = 3,
    /** Room wait: after this many fruitless attempts, stop opening Google Photos by itself (notify instead). */
    val autoMaxFruitless: Int = 2,
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
    private var isFinal = false
    /** Attempts in a row after which nothing left the Pixel. */
    @Volatile var fruitless = 0; private set
    private var onPixelAtAttempt = -1
    /** Final wave gave up: Google hasn't freed the rest (yet). */
    @Volatile var gaveUp = false; private set

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
        isFinal = true
    }

    private fun start(bytes: Long, done: () -> Boolean) {
        phase = Phase.WAITING; neededBytes = bytes; goal = done; isFinal = false
        waitStarted = clock(); attempts = 0; lastAttempt = 0; stuckReported = false
        fruitless = 0; onPixelAtAttempt = -1; gaveUp = false
    }

    private fun delayFor(attempt: Int): Long = when (attempt) {
        0, 1 -> retryMs
        2 -> retryMs * 5 / 3
        3 -> retryMs * 10 / 3
        else -> retryMs * 5
    }

    /** One step of the waiting loop. @return true when the goal is reached. */
    @Synchronized fun tick(): Boolean {
        if (phase == Phase.RUNNING) return true
        val rec = tracker.reconcile()
        if (goal()) {
            phase = Phase.RUNNING; waveNumber++; notifier.resumed(); return true
        }
        val now = clock()
        // judge the previous attempt once it has had time to work
        if (onPixelAtAttempt >= 0 && rec.stillOnPixel >= 0 && now - lastAttempt >= minOf(retryMs, 5 * 60_000L)) {
            if (rec.stillOnPixel < onPixelAtAttempt) fruitless = 0 else fruitless++
            onPixelAtAttempt = -1
            if (isFinal && fruitless >= finalMaxFruitless) { gaveUp = true; phase = Phase.RUNNING; return true }
        }
        if (now - lastFileAt < settleMs) return false
        val busy = signals.backingUp()
        val idleLongEnough = when (busy) {
            true -> false
            false -> now - signals.lastActivityMs() >= quietMs
            null -> now - waitStarted >= quietMs * 3 // no notification access: just wait longer
        }
        if (idleLongEnough && (lastAttempt == 0L || now - lastAttempt >= delayFor(attempts))) {
            attempts++; lastAttempt = now
            onPixelAtAttempt = rec.stillOnPixel
            val auto = freeUp.requestFreeUp(autoTap() && fruitless < autoMaxFruitless)
            notifier.waiting(neededBytes, engine.freeBytes(), attempts)
            if (!auto) { /* user notified to tap Free up space; window is open for them */ }
        }
        if (fruitless >= 2 && !stuckReported) {
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
