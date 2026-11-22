package app.pixelferry.core

import java.io.File

/**
 * Decides when a Pixel copy counts as "backed up by Google".
 *
 * Google gives apps no API for that. The one signal that cannot lie: Google Photos'
 * "Free up space" deletes ONLY items it has already backed up. So a copy is BACKED_UP only if:
 *   1. it vanished inside a short window that opened when Free up space was really confirmed
 *      (helper saw/pressed Google's confirm button, or you chose "Free up now"),
 *   2. it was on the Pixel when the window opened (anything already missing is MISSING_UNKNOWN),
 *   3. it was not just moved (same name + size found elsewhere on the Pixel),
 *   4. the free space actually went UP by (about) the size of what vanished. Trashing a file
 *      in Google Photos or a gallery moves it on the same partition and frees nothing, so a
 *      trashed-not-backed-up photo can't pass,
 *   5. the Pixel's storage was readable at the time.
 * Everything else is MISSING_UNKNOWN: the source is kept and the file is sent again next time.
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
    /** Rebuild the "elsewhere" index (called once per reconcile pass that needs it). */
    private val refreshElsewhere: () -> Unit = {},
    /** Does a file with this name and size exist outside PixelFerry's folder (moved, not freed)? */
    private val foundElsewhere: (String, Long) -> Boolean = { _, _ -> false },
    /** Free space on the Pixel. null = skip the space check (unit tests that don't model space only). */
    private val freeBytes: (() -> Long)? = null,
) {
    @Volatile var windowOpenUntil: Long = 0
        private set
    private var snapshot: Set<Long> = emptySet()
    private var freeAtOpen = 0L
    private var storedSinceOpen = 0L
    private var acceptedBytes = 0L

    /** The engine reports every new file so its bytes don't hide freed space. */
    @Synchronized fun noteStored(bytes: Long) { if (isWindowOpen()) storedSinceOpen += bytes }

    @Synchronized fun openFreeUpWindow() {
        reconcileLocked(windowOpen = false) // anything already gone is NOT Google's doing
        snapshot = ledger.byState(ItemState.VERIFIED).filter { it.pixelPath != null && exists(it.pixelPath) }.map { it.id }.toSet()
        freeAtOpen = freeBytes?.invoke() ?: 0; storedSinceOpen = 0; acceptedBytes = 0
        windowOpenUntil = clock() + windowMs
        onWindowChanged(windowOpenUntil)
    }

    @Synchronized fun closeWindow() { windowOpenUntil = 0; snapshot = emptySet(); onWindowChanged(0) }

    /** After an app restart the snapshot is gone, so a restored window stays closed (safe side). */
    fun restoreWindow(@Suppress("UNUSED_PARAMETER") until: Long) { windowOpenUntil = 0 }

    fun isWindowOpen() = clock() <= windowOpenUntil

    data class Reconcile(val backedUp: Int, val missing: Int, val stillOnPixel: Int)

    @Synchronized fun reconcile(): Reconcile = reconcileLocked(isWindowOpen())

    private fun reconcileLocked(windowOpen: Boolean): Reconcile {
        if (!storageReadable()) return Reconcile(0, 0, -1)
        val verified = ledger.byState(ItemState.VERIFIED)
        val gone = verified.filter { it.pixelPath != null && !exists(it.pixelPath) }
        val still = verified.size - gone.size
        if (gone.isEmpty()) return Reconcile(0, 0, still)
        refreshElsewhere()
        val candidates = gone.filter { windowOpen && it.id in snapshot && !foundElsewhere(File(it.pixelPath!!).name, it.size) }
        val batch = candidates.sumOf { it.size }
        // Did the space really come back? (80%: filesystem blocks, thumbnails, rounding)
        val fb = freeBytes
        val spaceConfirms = candidates.isNotEmpty() &&
            (fb == null || fb() - freeAtOpen + storedSinceOpen >= ((acceptedBytes + batch) * 8) / 10)
        val backed = if (spaceConfirms) candidates.map { it.id }.toSet() else emptySet()
        if (spaceConfirms) acceptedBytes += batch
        val updates = gone.associate { it.id to if (it.id in backed) ItemState.BACKED_UP else ItemState.MISSING_UNKNOWN }
        ledger.setStates(updates)
        return Reconcile(backed.size, gone.size - backed.size, still)
    }
}
