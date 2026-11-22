package app.pixelferry.core

import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class TransferLoopTest {
    /** Fake phone: can be unplugged mid-run, can corrupt a copy once. */
    class FakePhone(val files: Map<String, ByteArray>) : CopySource {
        var connected = true
        var unplugAfter = Int.MAX_VALUE
        var corruptOnce = mutableSetOf<String>()
        var copies = 0
        val deleted = mutableSetOf<String>()
        override fun isConnected() = connected
        override fun copyTo(entry: SourceEntry, dest: File) {
            if (!connected) throw IOException("gone")
            if (copies++ >= unplugAfter) { connected = false; dest.writeBytes(files[entry.ref]!!.copyOf(10)); throw IOException("unplugged") }
            val b = files[entry.ref]!!
            if (corruptOnce.remove(entry.ref)) dest.writeBytes(b.copyOf(b.size - 1)) else dest.writeBytes(b)
        }
        fun entries() = files.entries.mapIndexed { i, (k, v) -> SourceEntry("DCIM/$k|${v.size}|$i", k, v.size.toLong(), 1_000L * i, "DCIM", k) }
    }

    private val noSignals = object : BackupSignals { override fun backingUp() = false; override fun lastActivityMs() = 0L }
    private val silent = object : WaveNotifier {
        override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {}
        override fun stuck(reason: String) {}
        override fun resumed() {}
    }

    @Test fun ten_thousand_files_with_unplug_resume_corruption_and_waves() {
        val rnd = Random(3)
        val files = (0 until 10_000).associate { "IMG_%05d.HEIC".format(it) to rnd.nextBytes(64 + rnd.nextInt(400)) }
        val phone = FakePhone(files)
        phone.corruptOnce += listOf("IMG_00010.HEIC", "IMG_05000.HEIC")
        phone.unplugAfter = 4_000

        val root = tempDir()
        val ledger = MemoryLedger()
        // small "Pixel": ~800 KB usable -> many waves
        val storage = FakeStorage(root, capacity = 900_000)
        val engine = IngestEngine(storage, ledger, reserveBytes = 100_000)
        var clock = 0L
        val tracker = BackupTracker(ledger, { clock })
        // Google Photos: every free-up removes everything VERIFIED (= backed up)
        val freeUp = object : FreeUpAction {
            var runs = 0
            override fun requestFreeUp(auto: Boolean): Boolean {
                runs++; tracker.openFreeUpWindow(); ledger.byState(ItemState.VERIFIED).forEach { File(it.pixelPath!!).delete() }; return true
            }
        }
        val wave = WaveCoordinator(engine, tracker, noSignals, freeUp, silent, { clock },
            quietMs = 0, retryMs = 0, settleMs = 0)
        val profile = DeviceProfile("iphone-1", "Mom", "iPhone13")
        fun loop() = TransferLoop(engine, wave, ledger, cancelled = { false }, retryDelayMs = 0, waveSleepMs = 0,
            beforeEach = { clock += 1000 })

        val first = loop()
        assertEquals(LoopEnd.UNPLUGGED, first.run(profile, phone, phone.entries()))
        assertTrue(first.progress.stored in 3_990..4_000)

        // plug back in, resume: nothing copied twice
        phone.connected = true; phone.unplugAfter = Int.MAX_VALUE
        val second = loop()
        assertEquals(LoopEnd.FINISHED, second.run(profile, phone, phone.entries()))
        assertEquals(0, second.progress.failed, second.progress.errors.joinToString())
        val all = ledger.byState(ItemState.VERIFIED) + ledger.byState(ItemState.BACKED_UP)
        assertEquals(10_000, all.size, "every single file made it")
        assertEquals(10_000, all.map { it.itemKey }.toSet().size)
        // byte-identical check on whatever is still on the Pixel, hash check for the rest
        for (it in all) assertEquals(Hashing.sha256(files[it.originalName]!!.inputStream()), it.sha256)
        assertTrue(freeUp.runs >= 3, "needed several waves: ${freeUp.runs}")
        assertTrue(phone.copies < 10_000 + 10, "no re-copies beyond the unplug + 2 corrupt retries: ${phone.copies}")
        assertEquals(0, root.walkTopDown().count { it.name.endsWith(".part") })
    }

    @Test fun cancel_stops_promptly() {
        val phone = FakePhone((0 until 50).associate { "a$it.jpg" to ByteArray(10) { 1 }.also { b -> b[0] = it.toByte() } })
        var n = 0
        val ledger = MemoryLedger()
        val engine = IngestEngine(FakeStorage(tempDir()), ledger, reserveBytes = 0)
        val wave = WaveCoordinator(engine, BackupTracker(ledger), noSignals, object : FreeUpAction { override fun requestFreeUp(auto: Boolean) = true }, silent)
        val loop = TransferLoop(engine, wave, ledger, cancelled = { n >= 10 }, onUpdate = { n = it.done })
        assertEquals(LoopEnd.CANCELLED, loop.run(DeviceProfile("d", "S", "Oppo"), phone, phone.entries()))
        val saved = ledger.byState(ItemState.VERIFIED).size
        assertTrue(saved in 10..13, "stops within the pipeline depth: $saved")
        assertEquals(saved, loop.progress.stored, "every file already received is verified and counted, none lost")
    }

    @Test fun stops_early_when_every_file_fails() {
        val phone = object : CopySource {
            var calls = 0
            override fun isConnected() = true
            override fun copyTo(entry: SourceEntry, dest: File) { calls++; throw IOException("device refused") }
        }
        val entries = (0 until 2320).map { SourceEntry("k$it", "IMG_$it.jpg", 100, it.toLong(), "DCIM", "$it") }
        val ledger = MemoryLedger()
        val engine = IngestEngine(FakeStorage(tempDir()), ledger, reserveBytes = 0)
        val wave = WaveCoordinator(engine, BackupTracker(ledger), noSignals, object : FreeUpAction { override fun requestFreeUp(auto: Boolean) = true }, silent)
        val loop = TransferLoop(engine, wave, ledger, cancelled = { false }, retryDelayMs = 0)
        assertEquals(LoopEnd.ALL_FAILING, loop.run(DeviceProfile("d", "S", "Pad"), phone, entries))
        assertTrue(phone.calls < 30, "gave up after a handful, not 2,320 files: ${phone.calls}")
        assertEquals(0, ledger.byState(ItemState.VERIFIED).size)
    }
}
