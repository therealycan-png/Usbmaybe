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
    /** Bytes actually copied in this run (for speed/ETA: excludes files skipped as already sent). */
    @Volatile var copiedBytes = 0L
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
}

enum class LoopEnd { FINISHED, CANCELLED, UNPLUGGED, ALL_FAILING, STUCK_FULL }

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
    companion object { const val FAIL_FAST = 6 }

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
                    // decide the outcome first; count it afterwards (a UI hiccup must never turn into a retry)
                    var outcome: (Progress) -> Unit
                    try {
                        val r = engine.commit(j.pending, null)
                        wave.fileStored()
                        outcome = { p -> when (r) {
                            is IngestResult.Stored -> { p.stored++; p.copiedBytes += e.approxSize }
                            is IngestResult.Duplicate -> { p.duplicates++; p.copiedBytes += e.approxSize }
                            is IngestResult.AlreadyHave -> p.skipped++
                        } }
                    } catch (ex: TooBigForGoogle) {
                        outcome = { p -> p.failed++; p.errors += ex.message ?: e.name }
                    } catch (t: Throwable) {
                        // bad copy (or disk hiccup): fetch it again later
                        if (j.attempt + 1 >= 3) outcome = { p -> p.failed++; p.errors += "${e.name}: ${t.message}" }
                        else { retries += e to j.attempt + 1; continue }
                    }
                    try { advance(e, outcome) } catch (_: Throwable) { /* progress display only */ }
                }
            }
        }
    }, "pf-verify").apply { isDaemon = true; start() }

    private var verifierThread: Thread? = null

    /** Hand a received file to the verifier; never blocks forever if the verifier died. */
    private fun hand(job: Job) {
        while (!queue.offer(job, 1, java.util.concurrent.TimeUnit.SECONDS)) {
            if (verifierThread?.isAlive != true) {
                if (job is Job.Work) engine.abandon(job.pending)
                throw IllegalStateException("verifier stopped")
            }
        }
    }

    /** Wait until everything received so far is verified and recorded. */
    private fun drain() {
        val l = java.util.concurrent.CountDownLatch(1)
        hand(Job.Barrier(l))
        while (!l.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
            if (verifierThread?.isAlive != true) throw IllegalStateException("verifier stopped")
        }
    }

    fun run(profile: DeviceProfile, source: CopySource, selection: List<SourceEntry>): LoopEnd {
        val entries = selection.sortedBy { it.dateMs }
        val p = progress
        p.total = entries.size; p.bytesTotal = entries.sumOf { it.approxSize }
        engine.cleanTemp()
        // one query up front instead of one per file on the cable thread
        done = ledger.forDevice(profile.deviceKey).filter { ledger.isDone(it) }.mapTo(HashSet()) { it.itemKey }
        val v = verifier().also { verifierThread = it }
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
            if (v.isAlive) { queue.offer(Job.Stop, 5, java.util.concurrent.TimeUnit.SECONDS); v.join(30_000) }
            // anything never verified is released (its temp file deleted); it's simply sent next time
            generateSequence { queue.poll() }.forEach { if (it is Job.Work) engine.abandon(it.pending) }
            engine.discardPrepared()
        }
    }

    /** Stage 1 for one file. @return non-null to end the run. */
    private fun transfer(profile: DeviceProfile, source: CopySource, e: SourceEntry, attempt0: Int): LoopEnd? {
        if (cancelled()) return LoopEnd.CANCELLED
        // Something is systematically wrong (device refuses, storage blocked): stop early and say so
        // instead of grinding through thousands of files. Nothing is lost: failed files stay on the device.
        if (progress.failed >= FAIL_FAST && progress.stored + progress.duplicates == 0) return LoopEnd.ALL_FAILING
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
                hand(Job.Work(pending, e, attempt))
                return null
            } catch (ex: NotEnoughSpace) {
                drain() // let pending files finish so their space is counted properly
                if (!engine.hasRoomFor(e.approxSize) && !wave.awaitRoom(e.approxSize, cancelled, waveSleepMs)) return if (wave.gaveUp) LoopEnd.STUCK_FULL else LoopEnd.CANCELLED
            } catch (ex: IOException) {
                if (!source.isConnected()) return LoopEnd.UNPLUGGED
                if (++attempt >= 3) { advance(e) { p -> p.failed++; p.errors += "${e.name}: ${ex.message}" }; return null }
                Thread.sleep(retryDelayMs)
            }
        }
    }
}
