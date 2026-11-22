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
    /** Told about every stored file (BackupTracker uses it to account for free space). */
    var onStored: (Long) -> Unit = {},
) {
    private val lock = Any()
    /** Bytes promised to transfers in progress (USB job + Wi-Fi uploads at the same time). */
    private var inFlight = 0L
    private val startedAt = System.currentTimeMillis()

    fun freeBytes(): Long = storage.freeBytes()

    fun hasRoomFor(size: Long): Boolean = synchronized(lock) { storage.freeBytes() - inFlight - size >= reserveBytes }

    /** The finished record for this file, or null if it still needs sending (never sent, or lost before backup). */
    fun alreadyHave(deviceKey: String, itemKey: String): Item? =
        ledger.find(deviceKey, itemKey)?.takeIf { ledger.isDone(it) }

    /**
     * @param expectedSha256 from the sender if it computed one (Wi-Fi path); null for USB
     *        (USB/MTP has no source hash, so we verify exact size + record the on-disk hash).
     * @param write fills the temp file with the source bytes.
     * @param size exact size, or [UNKNOWN_SIZE] when the source can't say (PTP files over 4 GB):
     *        then the video's box structure must be complete instead.
     */
    fun ingest(
        profile: DeviceProfile,
        itemKey: String,
        originalName: String,
        size: Long,
        dateTakenMs: Long,
        expectedSha256: String?,
        write: (File) -> Unit,
    ): IngestResult {
        alreadyHave(profile.deviceKey, itemKey)?.let { return IngestResult.AlreadyHave(it) }
        val sizeKnown = size != UNKNOWN_SIZE
        val need = if (sizeKnown) size else BIG_FILE_ESTIMATE
        synchronized(lock) {
            if (storage.freeBytes() - inFlight - need < reserveBytes) throw NotEnoughSpace(need + reserveBytes, storage.freeBytes())
            inFlight += need
        }
        val dir = File(storage.rootDir(), Naming.folderName(profile.owner, profile.label))
        val tmp = File(dir, ".pf-${System.nanoTime()}.part")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir (storage permission?)")
            write(tmp)
            // Force the bytes onto flash BEFORE we verify or record anything (power loss safety).
            RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            val len = tmp.length()
            if (len > GOOGLE_MAX_BYTES) throw TooBigForGoogle(originalName, len)
            if (sizeKnown && len != size) throw VerifyFailed("$originalName: size $len != expected $size")
            if (!sizeKnown && !(MediaTypes.isVideo(originalName) && Mp4Check.isComplete(tmp))) {
                throw VerifyFailed("$originalName: over 4 GB and arrived incomplete")
            }
            val sha = Hashing.sha256(tmp)
            if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
                throw VerifyFailed("$originalName: checksum mismatch")
            }
            synchronized(lock) {
                alreadyHave(profile.deviceKey, itemKey)?.let { tmp.delete(); return IngestResult.AlreadyHave(it) }
                // a lost (MISSING_UNKNOWN) or orphaned earlier record is replaced by this good copy
                ledger.find(profile.deviceKey, itemKey)?.let { ledger.remove(it.id) }
                val original = ledger.findByHash(sha)?.takeIf { o ->
                    o.state == ItemState.BACKED_UP || o.state == ItemState.SOURCE_DELETED ||
                        (o.state == ItemState.VERIFIED && o.pixelPath != null && exists(o.pixelPath))
                }
                if (original != null) {
                    tmp.delete()
                    val dup = ledger.insert(
                        Item(deviceKey = profile.deviceKey, itemKey = itemKey, originalName = originalName,
                            size = len, dateTakenMs = dateTakenMs, pixelPath = null, sha256 = sha,
                            state = ItemState.DUPLICATE, duplicateOf = original.id)
                    )
                    return IngestResult.Duplicate(dup)
                }
                val target = Naming.uniqueFile(dir, Naming.targetName(profile.owner, profile.label, originalName))
                if (!tmp.renameTo(target)) throw IOException("rename failed for $target")
                storage.syncDir(dir)
                if (dateTakenMs > 0) target.setLastModified(dateTakenMs)
                val item = try {
                    ledger.insert(
                        Item(deviceKey = profile.deviceKey, itemKey = itemKey, originalName = originalName,
                            size = len, dateTakenMs = dateTakenMs, pixelPath = target.absolutePath,
                            sha256 = sha, state = ItemState.VERIFIED)
                    )
                } catch (e: Exception) {
                    target.delete() // never leave a file the ledger doesn't know about
                    throw IOException("could not record ${target.name}: ${e.message}", e)
                }
                onStored(len)
                storage.publish(target, MediaTypes.mimeFor(target.name), dateTakenMs)
                return IngestResult.Stored(item)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
            synchronized(lock) { inFlight -= need }
        }
    }

    /** Remove temp files left by a crashed earlier run (never ones being written now). */
    fun cleanTemp() {
        storage.rootDir().walkTopDown()
            .filter { it.isFile && it.name.startsWith(".pf-") && it.name.endsWith(".part") && it.lastModified() < startedAt - 60_000 }
            .forEach { it.delete() }
    }
}
