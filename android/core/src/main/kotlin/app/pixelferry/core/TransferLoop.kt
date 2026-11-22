package app.pixelferry.core

import java.io.File
import java.io.IOException

/** One photo/video on a source device. */
data class SourceEntry(
    /** Stable across reconnects: storage + path + size + date. */
    val itemKey: String,
    val name: String,
    /** Exact size, or [UNKNOWN_SIZE] (PTP objects over 4 GB). */
    val size: Long,
    val dateMs: Long,
    /** Folder path on the device, e.g. "DCIM/100APPLE". */
    val folder: String,
    /** Opaque handle (MTP object handle or document URI). */
    val ref: String,
) {
    /** For progress and space math. */
    val approxSize: Long get() = if (size >= 0) size else BIG_FILE_ESTIMATE
}

/** The part of a source the copy loop needs. */
interface CopySource {
    fun isConnected(): Boolean
    /** Write the exact original bytes into [dest]. */
    fun copyTo(entry: SourceEntry, dest: File)
}

class Progress {
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var bytesTotal = 0L
    @Volatile var bytesDone = 0L
    @Volatile var stored = 0
    @Volatile var duplicates = 0
    @Volatile var skipped = 0
    @Volatile var failed = 0
    @Volatile var current = ""
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
}

enum class LoopEnd { FINISHED, CANCELLED, UNPLUGGED }

/**
 * Copies a selection oldest-first. Every file: skip if done -> make room (wave) ->
 * ingest (verify) -> retry up to 3x on a bad copy -> pause cleanly if the cable is pulled.
 */
class TransferLoop(
    private val engine: IngestEngine,
    private val wave: WaveCoordinator,
    private val ledger: Ledger,
    private val cancelled: () -> Boolean,
    private val beforeEach: () -> Unit = {},
    private val onUpdate: (Progress) -> Unit = {},
    private val retryDelayMs: Long = 2000,
    private val waveSleepMs: Long = 20_000,
) {
    val progress = Progress()

    fun run(profile: DeviceProfile, source: CopySource, selection: List<SourceEntry>): LoopEnd {
        val entries = selection.sortedBy { it.dateMs }
        val p = progress
        p.total = entries.size; p.bytesTotal = entries.sumOf { it.approxSize }
        engine.cleanTemp()
        for (e in entries) {
            if (cancelled()) return LoopEnd.CANCELLED
            if (engine.alreadyHave(profile.deviceKey, e.itemKey) != null) { p.skipped++; advance(e); continue }
            beforeEach()
            if (!engine.hasRoomFor(e.approxSize) && !wave.awaitRoom(e.approxSize, cancelled, waveSleepMs)) return LoopEnd.CANCELLED
            var attempt = 0
            while (true) {
                if (cancelled()) return LoopEnd.CANCELLED
                try {
                    p.current = e.name; onUpdate(p)
                    when (engine.ingest(profile, e.itemKey, e.name, e.size, e.dateMs, null) { source.copyTo(e, it) }) {
                        is IngestResult.Stored -> p.stored++
                        is IngestResult.Duplicate -> p.duplicates++
                        is IngestResult.AlreadyHave -> p.skipped++
                    }
                    wave.fileStored()
                    break
                } catch (ex: TooBigForGoogle) {
                    p.failed++; p.errors += ex.message ?: e.name; break
                } catch (ex: NotEnoughSpace) {
                    if (!wave.awaitRoom(e.approxSize, cancelled, waveSleepMs)) return LoopEnd.CANCELLED
                } catch (ex: VerifyFailed) {
                    if (!source.isConnected()) return LoopEnd.UNPLUGGED
                    if (++attempt >= 3) { p.failed++; p.errors += "${e.name}: ${ex.message}"; break }
                } catch (ex: IOException) {
                    if (!source.isConnected()) return LoopEnd.UNPLUGGED
                    if (++attempt >= 3) { p.failed++; p.errors += "${e.name}: ${ex.message}"; break }
                    Thread.sleep(retryDelayMs)
                }
            }
            advance(e)
        }
        return LoopEnd.FINISHED
    }

    private fun advance(e: SourceEntry) {
        progress.done++; progress.bytesDone += e.approxSize; onUpdate(progress)
    }
}
