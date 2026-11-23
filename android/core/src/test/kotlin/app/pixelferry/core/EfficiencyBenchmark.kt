package app.pixelferry.core

import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

/**
 * Transfer efficiency per RFC 6349 (IETF "Framework for TCP Throughput Testing", sec. 4.1):
 *   Transfer Time Ratio = actual transfer time / ideal transfer time
 *   ideal transfer time = payload bytes / bottleneck bandwidth
 *   efficiency          = ideal / actual  (100% = the link never sits idle)
 *
 * The bottleneck is a simulated USB link (default 40 MB/s, a typical MTP rate over USB on
 * a Pixel XL). Everything PixelFerry adds (fsync, SHA-256 verify, dedupe, ledger, rename,
 * media publish) counts against it. Result is written to build/efficiency.txt.
 */
class EfficiencyBenchmark {
    /** Rate-limited fake phone: the "wire". */
    class Link(private val bytesPerSec: Long) : CopySource {
        private var busyUntilNs = 0L
        private val noise = Random(1).nextBytes(1 shl 20)
        override fun isConnected() = true
        /** Wall time spent inside the simulated wire (incl. simulator sleep jitter). */
        var insideNs = 0L
        override fun copyTo(entry: SourceEntry, dest: File) {
            val t0 = System.nanoTime()
            try { copyInner(entry, dest) } finally { insideNs += System.nanoTime() - t0 }
        }
        private fun copyInner(entry: SourceEntry, dest: File) {
            val size = entry.size.toInt()
            val id = entry.ref.toInt()
            dest.outputStream().use { o ->
                var off = 0
                val chunk = 256 * 1024
                val buf = ByteArray(chunk)
                while (off < size) {
                    val n = minOf(chunk, size - off)
                    // unique, incompressible content per file without holding it all in memory
                    System.arraycopy(noise, (off + id * 7919) % (noise.size - chunk), buf, 0, n)
                    buf[0] = id.toByte(); buf[1] = (id shr 8).toByte(); buf[2] = (off shr 18).toByte()
                    // the wire delivers n bytes no faster than the bandwidth allows
                    // Within a file the USB pipe is buffered (host controller + device FIFO), so a late reader
                    // still gets data that arrived meanwhile (up to ~1 MB). Between files there is NO credit:
                    // a real MTP device waits for the next GetObject, so any per-file gap counts against us.
                    val now = System.nanoTime()
                    val credit = if (off == 0) 0L else (1L shl 20) * 1_000_000_000L / bytesPerSec
                    val start = maxOf(now - credit, busyUntilNs).let { if (off == 0) maxOf(it, now) else it }
                    busyUntilNs = start + n * 1_000_000_000L / bytesPerSec
                    // precise pacing: coarse sleep, then spin the last ~1 ms (OS sleep overshoot is not the app's fault)
                    val coarse = busyUntilNs - System.nanoTime() - 1_000_000
                    if (coarse > 0) Thread.sleep(coarse / 1_000_000, (coarse % 1_000_000).toInt())
                    while (System.nanoTime() < busyUntilNs) Thread.onSpinWait()
                    o.write(buf, 0, n); off += n
                }
            }
        }
    }

    private val silent = object : WaveNotifier {
        override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {}
        override fun stuck(reason: String) {}
        override fun resumed() {}
    }

    data class Result(val name: String, val files: Int, val bytes: Long, val idealMs: Long, val actualMs: Long, val wireMs: Long, val plainMs: Long = 0) {
        val vsPlain get() = plainMs * 100.0 / actualMs
        val efficiency get() = idealMs * 100.0 / actualMs
        /** Time the wire sat idle because of PixelFerry (actual minus time inside the wire). */
        val overheadMs get() = actualMs - wireMs
        /** Efficiency with the simulator's own sleep jitter removed: ideal / (ideal + PixelFerry overhead). */
        val appEfficiency get() = idealMs * 100.0 / (idealMs + overheadMs)
        override fun toString() = ("%-28s %4d files %6.1f MB | ideal %5d ms | plain copy %5d ms | PixelFerry %5d ms | " +
            "RFC6349 efficiency %.1f%% | vs plain copy %.1f%%").format(name, files, bytes / 1e6, idealMs, plainMs, actualMs, efficiency, vsPlain)
    }

    /** Baseline: a plain copy tool over the same wire (no fsync, no checksum, no ledger, no dedupe). */
    private fun plainCopyMs(sizes: List<Int>, bytesPerSec: Long): Long {
        val link = Link(bytesPerSec); val dir = tempDir()
        val t0 = System.nanoTime()
        sizes.forEachIndexed { i, sz -> link.copyTo(SourceEntry("$i", "f$i", sz.toLong(), 0, "", "$i"), File(dir, "f$i")) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        dir.deleteRecursively(); return ms
    }

    private fun run(name: String, sizes: List<Int>, bytesPerSec: Long): Result {
        val link = Link(bytesPerSec)
        val entries = sizes.mapIndexed { i, sz -> SourceEntry("DCIM/$i|$sz", "IMG_%05d.HEIC".format(i), sz.toLong(), i * 1000L, "DCIM", "$i") }
        val ledger = MemoryLedger()
        val root = tempDir()
        val storage = object : PixelStorage {
            override fun rootDir() = root
            override fun freeBytes() = Long.MAX_VALUE / 4
            override fun publish(file: File, mime: String, dateTakenMs: Long) {}
        }
        val engine = IngestEngine(storage, ledger, reserveBytes = 0)
        val wave = WaveCoordinator(engine, BackupTracker(ledger), silent)
        val loop = TransferLoop(engine, wave, ledger, cancelled = { false })
        val total = sizes.sumOf { it.toLong() }
        val t0 = System.nanoTime()
        val end = loop.run(DeviceProfile("bench", "Bench", "Phone"), link, entries)
        val actual = (System.nanoTime() - t0) / 1_000_000
        assertEquals(LoopEnd.FINISHED, end)
        assertEquals(sizes.size, loop.progress.stored, "every file stored + verified")
        root.deleteRecursively()
        return Result(name, sizes.size, total, total * 1000 / bytesPerSec, actual, link.insideNs / 1_000_000, plainCopyMs(sizes, bytesPerSec))
    }

    @Test fun measure() {
        val mbps = (System.getProperty("pf.bench.mbps") ?: "40").toLong() * 1_000_000
        val scale = (System.getProperty("pf.bench.scale") ?: "1").toInt()
        val rnd = Random(5)
        val photos = List(60 * scale) { 2_000_000 + rnd.nextInt(4_000_000) }        // 2–6 MB HEIC/JPEG
        val videos = List(4 * scale) { 40_000_000 + rnd.nextInt(60_000_000) }       // 40–100 MB clips
        val small = List(300 * scale) { 150_000 + rnd.nextInt(350_000) }            // screenshots, WhatsApp images
        val results = listOf(
            run("camera roll (photos+videos)", (photos + videos).shuffled(rnd), mbps),
            run("many small images", small, mbps),
        )
        val report = buildString {
            appendLine("PixelFerry transfer efficiency (RFC 6349 transfer-time ratio), link ${mbps / 1_000_000} MB/s")
            results.forEach { appendLine(it) }
            appendLine("OVERALL  RFC 6349 efficiency %.1f%%  |  vs plain copy %.1f%%  (PixelFerry verifies every byte; plain copy verifies nothing)".format(
                results.sumOf { it.idealMs } * 100.0 / results.sumOf { it.actualMs },
                results.sumOf { it.plainMs } * 100.0 / results.sumOf { it.actualMs }))
        }
        println(report)
        File("build/efficiency.txt").writeText(report)
    }
}
