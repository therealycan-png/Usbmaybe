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
    /** Same, but returns the SHA-256 of the bytes if the source streamed them through our code. */
    fun copyHashed(entry: SourceEntry, dest: File): String? { copyTo(entry, dest); return null }
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
 * Copies a selection oldest-first, as a two-stage pipeline so the cable never waits for the disk:
 *
 *   cable thread:  [receive file 1][receive file 2][receive file 3] ...
 *   verify thread:                 [verify+record 1][verify+record 2] ...
 *
 * Verification is exactly as strict as before (fsync, size/structure, SHA-256, dedupe, ledger);
 * it just overlaps the next transfer. A bad copy goes to a retry list (up to 3 tries), a full
 * Pixel drains the pipeline and starts a wave, a pulled cable drains and pauses cleanly.
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
    /** How many received files may wait for verification (bounds extra temp space). */
    private val pipelineDepth: Int = 2,
) {
    val progress = Progress()
    private var done: Set<String> = emptySet()

    private sealed class Job {
        class Work(val pending: IngestEngine.Pending, val entry: SourceEntry, val attempt: Int) : Job()
        class Barrier(val latch: java.util.concurrent.CountDownLatch) : Job()
        object Stop : Job()
    }

    private val queue = java.util.concurrent.ArrayBlockingQueue<Job>(maxOf(1, pipelineDepth))
    private val retries = java.util.concurrent.ConcurrentLinkedQueue<Pair<SourceEntry, Int>>()

    @Synchronized private fun advance(e: SourceEntry, how: (Progress) -> Unit = {}) {
        how(progress); progress.done++; progress.bytesDone += e.approxSize; onUpdate(progress)
    }

    private fun verifier() = Thread({
        while (true) {
            when (val j = queue.take()) {
                is Job.Stop -> return@Thread
                is Job.Barrier -> j.latch.countDown()
                is Job.Work -> {
                    val e = j.entry
                    try {
                        val r = engine.commit(j.pending, null)
                        wave.fileStored()
                        advance(e) { p -> when (r) {
                            is IngestResult.Stored -> p.stored++
                            is IngestResult.Duplicate -> p.duplicates++
                            is IngestResult.AlreadyHave -> p.skipped++
                        } }
                    } catch (ex: TooBigForGoogle) {
                        advance(e) { p -> p.failed++; p.errors += ex.message ?: e.name }
                    } catch (ex: Exception) {
                        // bad copy (or disk hiccup): fetch it again later
                        if (j.attempt + 1 >= 3) advance(e) { p -> p.failed++; p.errors += "${e.name}: ${ex.message}" }
                        else retries += e to j.attempt + 1
                    }
                }
            }
        }
    }, "pf-verify").apply { isDaemon = true; start() }

    /** Wait until everything received so far is verified and recorded. */
    private fun drain() {
        val l = java.util.concurrent.CountDownLatch(1)
        queue.put(Job.Barrier(l)); l.await()
    }

    fun run(profile: DeviceProfile, source: CopySource, selection: List<SourceEntry>): LoopEnd {
        val entries = selection.sortedBy { it.dateMs }
        val p = progress
        p.total = entries.size; p.bytesTotal = entries.sumOf { it.approxSize }
        engine.cleanTemp()
        // one query up front instead of one per file on the cable thread
        done = ledger.forDevice(profile.deviceKey).filter { ledger.isDone(it) }.mapTo(HashSet()) { it.itemKey }
        val v = verifier()
        try {
            var work: List<Pair<SourceEntry, Int>> = entries.map { it to 0 }
            while (work.isNotEmpty()) {
                for ((e, attempt0) in work) {
                    val end = transfer(profile, source, e, attempt0)
                    if (end != null) { drain(); return end }
                }
                drain()
                work = generateSequence { retries.poll() }.toList()
            }
            return LoopEnd.FINISHED
        } finally {
            queue.put(Job.Stop); v.join()
            engine.discardPrepared()
        }
    }

    /** Stage 1 for one file. @return non-null to end the run. */
    private fun transfer(profile: DeviceProfile, source: CopySource, e: SourceEntry, attempt0: Int): LoopEnd? {
        if (cancelled()) return LoopEnd.CANCELLED
        if (e.itemKey in done) { advance(e) { it.skipped++ }; return null }
        beforeEach()
        var attempt = attempt0
        while (true) {
            if (cancelled()) return LoopEnd.CANCELLED
            try {
                progress.current = e.name; onUpdate(progress)
                val pending = engine.receive(profile, e.itemKey, e.name, e.size, e.dateMs, checkDone = false, prepareAhead = true) { source.copyHashed(e, it) }
                if (pending == null) { advance(e) { it.skipped++ }; return null }
                if (!source.isConnected()) { engine.abandon(pending); return LoopEnd.UNPLUGGED } // may be cut short
                queue.put(Job.Work(pending, e, attempt))
                return null
            } catch (ex: NotEnoughSpace) {
                drain() // let pending files finish so their space is counted properly
                if (!engine.hasRoomFor(e.approxSize) && !wave.awaitRoom(e.approxSize, cancelled, waveSleepMs)) return LoopEnd.CANCELLED
            } catch (ex: IOException) {
                if (!source.isConnected()) return LoopEnd.UNPLUGGED
                if (++attempt >= 3) { advance(e) { p -> p.failed++; p.errors += "${e.name}: ${ex.message}" }; return null }
                Thread.sleep(retryDelayMs)
            }
        }
    }
}
