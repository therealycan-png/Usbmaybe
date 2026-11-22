package app.pixelferry.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Where files land on the Pixel. Android impl = DCIM + MediaScanner + StatFs. */
interface PixelStorage {
    fun rootDir(): File
    fun freeBytes(): Long
    /** Called once the final file is in place (Android: media-scan it so Google Photos sees it). */
    fun publish(file: File, mime: String, dateTakenMs: Long)
    /** Make the folder entry (the rename) durable. */
    fun syncDir(dir: File) {
        try { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } } catch (_: Exception) {}
    }
}

/** A video Google Photos will never back up (over 10 GB): keeping it on the Pixel would block the waves. */
class TooBigForGoogle(name: String, len: Long) : IOException("$name is ${len / (1L shl 30)} GB: Google Photos can't back up videos over 10 GB. Kept on the source.")

const val GOOGLE_MAX_BYTES = 10L * 1024 * 1024 * 1024

/** Pass as size when the source cannot report it (PTP objects over 4 GB). */
const val UNKNOWN_SIZE = -1L
/** Room to reserve for a file of unknown size. */
const val BIG_FILE_ESTIMATE = 12L * 1024 * 1024 * 1024

class NotEnoughSpace(val needed: Long, val free: Long) : IOException("Pixel full: need $needed, free $free")
class VerifyFailed(msg: String) : IOException(msg)

sealed class IngestResult {
    data class Stored(val item: Item) : IngestResult()
    data class Duplicate(val item: Item) : IngestResult()
    data class AlreadyHave(val item: Item) : IngestResult()
}

/**
 * The one path every file takes, USB or Wi-Fi:
 *   space check -> write temp -> read back + SHA-256 -> compare -> dedupe -> rename -> publish -> ledger
 * A file is never in the ledger unless its bytes on disk were verified.
 */
class IngestEngine(
    private val storage: PixelStorage,
    private val ledger: Ledger,
    /** Always keep this much free so Android and Google Photos keep working. */
    val reserveBytes: Long = 1_500L * 1024 * 1024,
    private val exists: (String) -> Boolean = { File(it).exists() },
    /** Told about every stored file: (bytes, System.nanoTime() when written to disk). BackupTracker uses it for free-space accounting. */
    var onStored: (Long, Long) -> Unit = { _, _ -> },
    var onWarning: (String) -> Unit = {},
) {
    /** Guards dedupe + ledger + rename (commit stage). */
    private val lock = Any()
    /** Guards only the space reservation, so the cable thread never waits on a commit. */
    private val spaceLock = Any()
    /** Bytes promised to transfers in progress (USB job + Wi-Fi uploads at the same time). */
    private var inFlight = 0L
    private val startedAt = System.currentTimeMillis()

    fun freeBytes(): Long = storage.freeBytes()

    fun hasRoomFor(size: Long): Boolean = synchronized(spaceLock) { storage.freeBytes() - inFlight - size >= reserveBytes }

    /** The finished record for this file, or null if it still needs sending (never sent, or lost before backup). */
    fun alreadyHave(deviceKey: String, itemKey: String): Item? =
        ledger.find(deviceKey, itemKey)?.takeIf { ledger.isDone(it) }

    /** A file that has arrived in a temp file but is not verified or recorded yet. */
    class Pending internal constructor(
        val profile: DeviceProfile, val itemKey: String, val originalName: String, val size: Long,
        val dateTakenMs: Long, internal val tmp: File, internal val dir: File, internal val need: Long,
        /** SHA-256 the writer computed from the exact bytes it wrote (streaming), if it could. */
        internal val writerSha: String?,
        /** When the bytes finished landing on disk (a free-up window opened later must not count them). */
        internal val writtenAt: Long = System.nanoTime(), // monotonic: immune to clock changes
    )

    /**
     * Stage 1 (talks to the cable / network): reserve space, write the bytes to a temp file.
     * @return null if this file is already done.
     * @param write writes the source bytes into the file; returns their SHA-256 if it hashed them on the way.
     */
    fun receive(profile: DeviceProfile, itemKey: String, originalName: String, size: Long, dateTakenMs: Long,
                checkDone: Boolean = true, prepareAhead: Boolean = false, write: (File) -> String?): Pending? {
        if (checkDone && alreadyHave(profile.deviceKey, itemKey) != null) return null
        val sizeKnown = size != UNKNOWN_SIZE
        val need = if (sizeKnown) size else BIG_FILE_ESTIMATE
        synchronized(spaceLock) {
            if (storage.freeBytes() - inFlight - need < reserveBytes) throw NotEnoughSpace(need + reserveBytes, storage.freeBytes())
            inFlight += need
        }
        val dir = dirFor(profile)
        var tmp = File(dir, ".pf-${System.nanoTime()}.part")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir (storage permission?)")
            if (prepareAhead) {
                tmp = takePrepared(dir) ?: tmp
                prepareNext(dir) // create the next temp file while this one transfers
            }
            val sha = write(tmp)
            return Pending(profile, itemKey, originalName, size, dateTakenMs, tmp, dir, need, sha)
        } catch (e: Throwable) {
            tmp.delete(); synchronized(spaceLock) { inFlight -= need }
            throw e
        }
    }

    /** One empty temp file created ahead of time per folder: file creation never sits on the cable thread. */
    private val prepared = java.util.concurrent.ConcurrentHashMap<File, java.util.concurrent.Future<File?>>()
    private val preparer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pf-prep").apply { isDaemon = true } }

    private fun takePrepared(dir: File): File? = try {
        prepared.remove(dir)?.get()?.takeIf { it.exists() && it.length() == 0L }
    } catch (_: Exception) { null }

    private fun prepareNext(dir: File) {
        prepared[dir] = preparer.submit<File?> {
            val f = File(dir, ".pf-${System.nanoTime()}.part")
            if (f.createNewFile()) f else null
        }
    }

    /** Delete spare temp files made ahead of time (end of a run). */
    fun discardPrepared() {
        for (k in prepared.keys.toList()) takePrepared(k)?.delete()
    }

    private val dirs = java.util.concurrent.ConcurrentHashMap<String, File>()
    private fun dirFor(profile: DeviceProfile): File =
        dirs.getOrPut(Naming.folderName(profile.owner, profile.label)) { File(storage.rootDir(), Naming.folderName(profile.owner, profile.label)) }

    /** Give up on a received file (cancel/unplug): nothing is recorded. */
    fun abandon(p: Pending) { p.tmp.delete(); synchronized(spaceLock) { inFlight -= p.need } }

    /**
     * Stage 2 (disk only, can overlap the next file's transfer):
     * fsync -> size/structure check -> SHA-256 -> compare -> dedupe -> rename -> ledger -> publish.
     * A file is never in the ledger unless all of that passed.
     * @param expectedSha256 the sender's own hash (Wi-Fi), compared end to end.
     */
    fun commit(p: Pending, expectedSha256: String?): IngestResult {
        val tmp = p.tmp; val originalName = p.originalName; val profile = p.profile
        var stored: Pair<File, Item>? = null
        try {
            // Force the bytes onto flash BEFORE we verify or record anything (power loss safety).
            RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            val len = tmp.length()
            if (len > GOOGLE_MAX_BYTES) throw TooBigForGoogle(originalName, len)
            if (p.size != UNKNOWN_SIZE && len != p.size) throw VerifyFailed("$originalName: size $len != expected ${p.size}")
            if (p.size == UNKNOWN_SIZE && !(MediaTypes.isVideo(originalName) && Mp4Check.isComplete(tmp))) {
                throw VerifyFailed("$originalName: over 4 GB and arrived incomplete")
            }
            // Bytes we streamed ourselves were hashed on the way; files written by the USB stack are read back.
            val sha = p.writerSha ?: Hashing.sha256(tmp)
            if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
                throw VerifyFailed("$originalName: checksum mismatch")
            }
            synchronized(lock) {
                alreadyHave(profile.deviceKey, p.itemKey)?.let { tmp.delete(); return IngestResult.AlreadyHave(it) }
                // a lost (MISSING_UNKNOWN) or orphaned earlier record is replaced by this good copy
                ledger.find(profile.deviceKey, p.itemKey)?.let { ledger.remove(it.id) }
                val original = ledger.findByHash(sha)?.takeIf { o ->
                    o.state == ItemState.BACKED_UP || o.state == ItemState.SOURCE_DELETED ||
                        (o.state == ItemState.VERIFIED && o.pixelPath != null && exists(o.pixelPath))
                }
                if (original != null) {
                    tmp.delete()
                    val dup = ledger.insert(
                        Item(deviceKey = profile.deviceKey, itemKey = p.itemKey, originalName = originalName,
                            size = len, dateTakenMs = p.dateTakenMs, pixelPath = null, sha256 = sha,
                            state = ItemState.DUPLICATE, duplicateOf = original.id)
                    )
                    return IngestResult.Duplicate(dup)
                }
                val target = Naming.uniqueFile(p.dir, Naming.targetName(profile.owner, profile.label, originalName))
                if (!tmp.renameTo(target)) throw IOException("rename failed for $target")
                if (p.dateTakenMs > 0) target.setLastModified(p.dateTakenMs)
                val item = try {
                    ledger.insert(
                        Item(deviceKey = profile.deviceKey, itemKey = p.itemKey, originalName = originalName,
                            size = len, dateTakenMs = p.dateTakenMs, pixelPath = target.absolutePath,
                            sha256 = sha, state = ItemState.VERIFIED)
                    )
                } catch (e: Exception) {
                    target.delete() // never leave a file the ledger doesn't know about
                    throw IOException("could not record ${target.name}: ${e.message}", e)
                }
                stored = target to item
            }
            // Outside the lock (slow, and safe in any order: if the rename were lost in a crash the
            // file would read as missing -> MISSING_UNKNOWN -> re-sent; it can never become "backed up").
            // The file is recorded now, so nothing below may throw (a retry would count it twice).
            val (target, item) = stored!!
            try { storage.syncDir(p.dir) } catch (_: Throwable) {}
            try { onStored(len, p.writtenAt) } catch (_: Throwable) {}
            try { storage.publish(target, MediaTypes.mimeFor(target.name), p.dateTakenMs) } catch (t: Throwable) {
                onWarning("Couldn't announce ${target.name} to Google Photos (${t.message}); it is saved and verified.")
            }
            return IngestResult.Stored(item)
        } finally {
            if (tmp.exists()) tmp.delete()
            synchronized(spaceLock) { inFlight -= p.need }
        }
    }

    /**
     * One file start to finish (both stages). The USB loop pipelines the stages instead.
     * @param expectedSha256 from the sender (Wi-Fi path); null for USB.
     * @param size exact size, or [UNKNOWN_SIZE] (PTP files over 4 GB: structure check instead).
     */
    fun ingest(profile: DeviceProfile, itemKey: String, originalName: String, size: Long, dateTakenMs: Long,
               expectedSha256: String?, write: (File) -> Unit): IngestResult =
        ingestHashed(profile, itemKey, originalName, size, dateTakenMs, expectedSha256) { write(it); null }

    fun ingestHashed(profile: DeviceProfile, itemKey: String, originalName: String, size: Long, dateTakenMs: Long,
                     expectedSha256: String?, write: (File) -> String?): IngestResult {
        alreadyHave(profile.deviceKey, itemKey)?.let { return IngestResult.AlreadyHave(it) }
        val p = receive(profile, itemKey, originalName, size, dateTakenMs, false, false, write)!! // checkDone=false never returns null
        return commit(p, expectedSha256)
    }

    /** Remove temp files left by a crashed earlier run (never ones being written now). */
    fun cleanTemp() {
        storage.rootDir().walkTopDown()
            .filter { it.isFile && it.name.startsWith(".pf-") && it.name.endsWith(".part") && it.lastModified() < startedAt - 60_000 }
            .forEach { it.delete() }
    }
}
