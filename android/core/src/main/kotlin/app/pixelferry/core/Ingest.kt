package app.pixelferry.core

import java.io.File
import java.io.IOException

/** Where files land on the Pixel. Android impl = DCIM + MediaScanner + StatFs. */
interface PixelStorage {
    fun rootDir(): File
    fun freeBytes(): Long
    /** Called once the final file is in place (Android: media-scan it so Google Photos sees it). */
    fun publish(file: File, mime: String, dateTakenMs: Long)
}

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
) {
    private val lock = Any()

    fun freeBytes(): Long = storage.freeBytes()

    fun hasRoomFor(size: Long): Boolean = storage.freeBytes() - size >= reserveBytes

    fun alreadyHave(deviceKey: String, itemKey: String): Item? = ledger.find(deviceKey, itemKey)

    /**
     * @param expectedSha256 from the sender if it computed one (Wi-Fi path); null for USB
     *        (USB/MTP has no source hash, so we verify exact size + record the on-disk hash).
     * @param write fills the temp file with the source bytes.
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
        ledger.find(profile.deviceKey, itemKey)?.let { return IngestResult.AlreadyHave(it) }
        if (!hasRoomFor(size)) throw NotEnoughSpace(size + reserveBytes, storage.freeBytes())

        val dir = File(storage.rootDir(), Naming.folderName(profile.owner, profile.label))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File(dir, ".pf-${System.nanoTime()}.part")
        try {
            write(tmp)
            val len = tmp.length()
            if (len != size) throw VerifyFailed("$originalName: size $len != expected $size")
            val sha = Hashing.sha256(tmp) // read back from flash = verifies what is actually stored
            if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
                throw VerifyFailed("$originalName: checksum mismatch")
            }
            synchronized(lock) {
                ledger.find(profile.deviceKey, itemKey)?.let { tmp.delete(); return IngestResult.AlreadyHave(it) }
                val original = ledger.findByHash(sha)
                if (original != null) {
                    tmp.delete()
                    val dup = ledger.insert(
                        Item(deviceKey = profile.deviceKey, itemKey = itemKey, originalName = originalName,
                            size = size, dateTakenMs = dateTakenMs, pixelPath = null, sha256 = sha,
                            state = ItemState.DUPLICATE, duplicateOf = original.id)
                    )
                    return IngestResult.Duplicate(dup)
                }
                val target = Naming.uniqueFile(dir, Naming.targetName(profile.owner, profile.label, originalName))
                if (!tmp.renameTo(target)) throw IOException("rename failed for $target")
                if (dateTakenMs > 0) target.setLastModified(dateTakenMs)
                storage.publish(target, MediaTypes.mimeFor(target.name), dateTakenMs)
                val item = ledger.insert(
                    Item(deviceKey = profile.deviceKey, itemKey = itemKey, originalName = originalName,
                        size = size, dateTakenMs = dateTakenMs, pixelPath = target.absolutePath,
                        sha256 = sha, state = ItemState.VERIFIED)
                )
                return IngestResult.Stored(item)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Remove stale temp files from an interrupted run. */
    fun cleanTemp() {
        storage.rootDir().walkTopDown().filter { it.isFile && it.name.startsWith(".pf-") && it.name.endsWith(".part") }
            .forEach { it.delete() }
    }
}
