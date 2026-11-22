package app.pixelferry.core

import java.io.File

/**
 * Decides when a Pixel copy counts as "backed up by Google".
 *
 * Google gives apps no API for that. The one signal that cannot lie: Google Photos'
 * "Free up space" deletes ONLY items it has already backed up. So:
 *   - we open a "free-up window" right before we (or you) run Free up space
 *   - VERIFIED files that disappear inside the window  -> BACKED_UP  (source deletable)
 *   - VERIFIED files that disappear outside any window -> MISSING_UNKNOWN (source kept forever)
 */
class BackupTracker(
    private val ledger: Ledger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 45 * 60 * 1000L,
    private val exists: (String) -> Boolean = { File(it).exists() },
    /** Persist the window so an app restart mid-free-up doesn't lose it. */
    private val onWindowChanged: (Long) -> Unit = {},
) {
    @Volatile var windowOpenUntil: Long = 0
        private set

    fun openFreeUpWindow() { windowOpenUntil = clock() + windowMs; onWindowChanged(windowOpenUntil) }

    fun restoreWindow(until: Long) { windowOpenUntil = until }

    fun isWindowOpen() = clock() <= windowOpenUntil

    data class Reconcile(val backedUp: Int, val missing: Int, val stillOnPixel: Int)

    fun reconcile(): Reconcile {
        var b = 0; var m = 0; var s = 0
        val open = isWindowOpen()
        for (item in ledger.byState(ItemState.VERIFIED)) {
            val p = item.pixelPath ?: continue
            if (exists(p)) { s++; continue }
            if (open) { ledger.setState(item.id, ItemState.BACKED_UP); b++ }
            else { ledger.setState(item.id, ItemState.MISSING_UNKNOWN); m++ }
        }
        return Reconcile(b, m, s)
    }
}
