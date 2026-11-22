package app.pixelferry.core

import java.io.File

/**
 * Decides when a Pixel copy counts as "backed up by Google".
 *
 * Google gives apps no API for that. The one signal that cannot lie: Google Photos'
 * "Free up space" deletes ONLY items it has already backed up. So:
 *   - a short "free-up window" opens ONLY when Free up space is actually being run:
 *     the helper is about to press Google's confirm button, or you tapped our
 *     "Free up now" notification/button. Retries and reminders never open it.
 *   - only files that were on the Pixel when the window opened can count (snapshot)
 *   - VERIFIED files that disappear inside the window    -> BACKED_UP  (source deletable)
 *   - VERIFIED files that disappear any other way        -> MISSING_UNKNOWN (source kept; re-sent next time)
 *   - a file that just moved (same name found elsewhere) -> MISSING_UNKNOWN
 *   - if the Pixel's storage can't be read at all, nothing changes
 */
class BackupTracker(
    private val ledger: Ledger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 10 * 60 * 1000L,
    private val exists: (String) -> Boolean = { File(it).exists() },
    /** Persist the window so an app restart mid-free-up doesn't lose it. */
    private val onWindowChanged: (Long) -> Unit = {},
    /** Is the Pixel's storage mounted and readable right now? */
    private val storageReadable: () -> Boolean = { true },
    /** Does a file with this name exist anywhere else (moved, not freed)? */
    private val foundElsewhere: (String) -> Boolean = { false },
) {
    @Volatile var windowOpenUntil: Long = 0
        private set
    private var snapshot: Set<Long> = emptySet()

    @Synchronized fun openFreeUpWindow() {
        windowOpenUntil = clock() + windowMs
        snapshot = ledger.byState(ItemState.VERIFIED).map { it.id }.toSet()
        onWindowChanged(windowOpenUntil)
    }

    @Synchronized fun closeWindow() { windowOpenUntil = 0; snapshot = emptySet(); onWindowChanged(0) }

    /** After an app restart the snapshot is gone, so a restored window stays closed (safe side). */
    fun restoreWindow(@Suppress("UNUSED_PARAMETER") until: Long) { windowOpenUntil = 0 }

    fun isWindowOpen() = clock() <= windowOpenUntil

    data class Reconcile(val backedUp: Int, val missing: Int, val stillOnPixel: Int)

    @Synchronized fun reconcile(): Reconcile {
        if (!storageReadable()) return Reconcile(0, 0, -1)
        var b = 0; var m = 0; var s = 0
        val open = isWindowOpen()
        for (item in ledger.byState(ItemState.VERIFIED)) {
            val p = item.pixelPath ?: continue
            if (exists(p)) { s++; continue }
            val freedByGoogle = open && item.id in snapshot && !foundElsewhere(File(p).name)
            if (freedByGoogle) { ledger.setState(item.id, ItemState.BACKED_UP); b++ }
            else { ledger.setState(item.id, ItemState.MISSING_UNKNOWN); m++ }
        }
        return Reconcile(b, m, s)
    }
}
