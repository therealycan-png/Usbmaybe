package app.pixelferry.core

import java.io.File
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class CoreTest {
    private val mom = DeviceProfile("dev-iphone", "Mom", "iPhone 13")

    @Test fun naming_and_insta360() {
        assertEquals("Mom_iPhone13", Naming.tag("Mom", "iPhone 13"))
        assertEquals("Mom_iPhone13_IMG_1234.HEIC", Naming.targetName("Mom", "iPhone 13", "IMG_1234.HEIC"))
        assertEquals("Shawn_LunaUltra_VID_001_insv.mp4", Naming.targetName("Shawn", "Luna Ultra", "VID_001.insv"))
        assertEquals("IMG_9_insp.jpg", MediaTypes.pixelSafeName("IMG_9.insp"))
        assertTrue(MediaTypes.isMedia("a.HEIC")); assertTrue(MediaTypes.isMedia("b.insv"))
        assertFalse(MediaTypes.isMedia("c.LRV")); assertFalse(MediaTypes.isMedia("IMG.AAE")); assertFalse(MediaTypes.isMedia(".hidden.jpg"))
        assertEquals("a_b_c.jpg", Naming.sanitizeFileName("a/b:c.jpg"))
    }

    @Test fun unique_never_overwrites() {
        val d = tempDir()
        File(d, "x.jpg").writeText("1")
        assertEquals("x~2.jpg", Naming.uniqueFile(d, "x.jpg").name)
    }

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    @Test fun ingest_verifies_dedupes_and_resumes() {
        val root = tempDir(); val ledger = MemoryLedger()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        val data = bytes(300_000, 1)
        val sha = Hashing.sha256(data.inputStream())

        val r1 = eng.ingest(mom, "k1", "IMG_1.HEIC", data.size.toLong(), 1_600_000_000_000, sha) { it.writeBytes(data) }
        val item = (r1 as IngestResult.Stored).item
        assertEquals(ItemState.VERIFIED, item.state)
        val f = File(item.pixelPath!!)
        assertContentEquals(data, f.readBytes(), "bytes must be identical")
        assertEquals("Mom_iPhone13_IMG_1.HEIC", f.name)
        assertEquals(1_600_000_000_000, f.lastModified())

        // resume: same key is skipped without writing
        var wrote = false
        assertIs<IngestResult.AlreadyHave>(eng.ingest(mom, "k1", "IMG_1.HEIC", data.size.toLong(), 0, sha) { wrote = true })
        assertFalse(wrote)

        // same bytes from another phone -> duplicate, no second copy
        val oppo = DeviceProfile("dev-oppo", "Shawn", "Oppo")
        val r3 = eng.ingest(oppo, "k9", "copy.HEIC", data.size.toLong(), 0, null) { it.writeBytes(data) }
        assertIs<IngestResult.Duplicate>(r3)
        assertEquals(1, root.walkTopDown().filter { it.isFile }.count())
    }

    @Test fun ingest_rejects_corruption_and_leaves_no_trace() {
        val root = tempDir(); val ledger = MemoryLedger()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        val data = bytes(10_000, 2)
        val sha = Hashing.sha256(data.inputStream())
        assertFailsWith<VerifyFailed> {
            eng.ingest(mom, "k", "a.jpg", data.size.toLong(), 0, sha) { it.writeBytes(data.copyOf().also { b -> b[5]++ }) }
        }
        assertFailsWith<VerifyFailed> {
            eng.ingest(mom, "k", "a.jpg", data.size.toLong(), 0, null) { it.writeBytes(data.copyOf(9_000)) }
        }
        assertNull(ledger.find(mom.deviceKey, "k"))
        assertEquals(0, root.walkTopDown().filter { it.isFile }.count(), "temp files cleaned")
    }

    @Test fun ingest_refuses_when_full() {
        val root = tempDir()
        val eng = IngestEngine(FakeStorage(root, capacity = 50_000), MemoryLedger(), reserveBytes = 10_000)
        assertFailsWith<NotEnoughSpace> { eng.ingest(mom, "k", "a.jpg", 45_000, 0, null) { it.writeBytes(ByteArray(45_000)) } }
    }

    @Test fun backup_tracker_only_trusts_free_up_window() {
        val clock = FakeClock(); val ledger = MemoryLedger()
        val root = tempDir()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        val a = (eng.ingest(mom, "a", "a.jpg", 3, 0, null) { it.writeBytes(byteArrayOf(1, 2, 3)) } as IngestResult.Stored).item
        val b = (eng.ingest(mom, "b", "b.jpg", 3, 0, null) { it.writeBytes(byteArrayOf(4, 5, 6)) } as IngestResult.Stored).item
        val dupOfA = eng.ingest(DeviceProfile("x", "S", "Pad"), "c", "c.jpg", 3, 0, null) { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val tracker = BackupTracker(ledger, clock::now)

        // user deletes b by hand, no free-up running -> must NOT unlock source deletion
        File(b.pixelPath!!).delete()
        tracker.reconcile()
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(b.id)!!.state)
        assertFalse(ledger.isSafeToDeleteFromSource(ledger.get(b.id)!!))
        assertFalse(ledger.isSafeToDeleteFromSource(ledger.get(a.id)!!), "still on Pixel = not proven backed up")

        // Google's Free up space removes a inside the window -> backed up
        tracker.openFreeUpWindow()
        File(a.pixelPath!!).delete()
        clock.advance(60_000)
        tracker.reconcile()
        assertEquals(ItemState.BACKED_UP, ledger.get(a.id)!!.state)
        assertTrue(ledger.isSafeToDeleteFromSource(ledger.get(a.id)!!))
        assertTrue(ledger.isSafeToDeleteFromSource((dupOfA as IngestResult.Duplicate).item), "duplicate follows its original")
        assertEquals(listOf("a"), ledger.deletableFor(mom.deviceKey).map { it.itemKey })
    }

    @Test fun wave_waits_for_quiet_then_frees_then_resumes() {
        val clock = FakeClock(); val ledger = MemoryLedger(); val root = tempDir()
        val storage = FakeStorage(root, capacity = 100_000)
        val eng = IngestEngine(storage, ledger, reserveBytes = 10_000)
        val item = (eng.ingest(mom, "a", "a.jpg", 80_000, 0, null) { it.writeBytes(ByteArray(80_000)) } as IngestResult.Stored).item
        var backingUp: Boolean? = true
        var lastAct = clock.now
        val freeUps = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        val tracker = BackupTracker(ledger, clock::now)
        val wave = WaveCoordinator(eng, tracker,
            object : BackupSignals { override fun backingUp() = backingUp; override fun lastActivityMs() = lastAct },
            object : FreeUpAction { override fun requestFreeUp(auto: Boolean): Boolean {
                freeUps += auto; tracker.openFreeUpWindow(); File(item.pixelPath!!).delete(); return true } }, // helper confirms, Google frees
            object : WaveNotifier {
                override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) { events += "wait$attempt" }
                override fun stuck(reason: String) { events += "stuck" }
                override fun resumed() { events += "resumed" }
            }, clock::now)
        wave.fileStored()
        wave.beginWait(50_000)
        assertFalse(wave.tick()); assertTrue(freeUps.isEmpty(), "never free up while Photos is still uploading")
        clock.advance(10 * 60_000); assertFalse(wave.tick()); assertTrue(freeUps.isEmpty())
        backingUp = false; lastAct = clock.now
        clock.advance(60_000); assertFalse(wave.tick()); assertTrue(freeUps.isEmpty(), "needs quiet period")
        clock.advance(5 * 60_000); assertFalse(wave.tick()); assertEquals(1, freeUps.size)
        assertTrue(wave.tick(), "space is back")
        assertEquals(ItemState.BACKED_UP, ledger.get(item.id)!!.state)
        assertEquals(WaveCoordinator.Phase.RUNNING, wave.phase)
        assertEquals(2, wave.waveNumber)
        assertTrue("resumed" in events)
    }

    @Test fun wave_reports_stuck_when_folder_not_backed_up() {
        val clock = FakeClock(); val ledger = MemoryLedger(); val root = tempDir()
        val eng = IngestEngine(FakeStorage(root, capacity = 100_000), ledger, reserveBytes = 10_000)
        eng.ingest(mom, "a", "a.jpg", 80_000, 0, null) { it.writeBytes(ByteArray(80_000)) }
        var stuck = ""
        val wave = WaveCoordinator(eng, BackupTracker(ledger, clock::now),
            object : BackupSignals { override fun backingUp() = false; override fun lastActivityMs() = 0L },
            object : FreeUpAction { override fun requestFreeUp(auto: Boolean) = true },
            object : WaveNotifier {
                override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {}
                override fun stuck(reason: String) { stuck = reason }
                override fun resumed() {}
            }, clock::now)
        wave.beginWait(50_000)
        repeat(6) { clock.advance(13 * 60_000); assertFalse(wave.tick()) }
        assertTrue(stuck.contains("Back up device folders"))
        assertEquals(0, ledger.deletableFor(mom.deviceKey).size, "nothing became deletable")
    }

    @Test fun pairing_code_format() {
        val t = Pairing.newToken(); assertEquals(8, t.length)
        val pin = Pairing.certPin(ByteArray(100) { it.toByte() })
        assertEquals(19, Pairing.code(t, pin.take(8)).length)
        assertEquals(16, pin.length)
        assertEquals(29, Pairing.code(t, pin).length)
        assertEquals("000", Pairing.base32(byteArrayOf(0, 0)).take(3))
    }
}

class Mp4CheckTest {
    private fun box(type: String, payload: Int): ByteArray {
        val size = 8 + payload
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray() + ByteArray(payload) { 7 }
    }

    @Test fun complete_vs_truncated_video() {
        val d = tempDir()
        val full = box("ftyp", 16) + box("mdat", 50_000) + box("moov", 900)
        val ok = File(d, "a.mov").apply { writeBytes(full) }
        assertTrue(Mp4Check.isComplete(ok))
        val cut = File(d, "b.mov").apply { writeBytes(full.copyOf(full.size - 300)) }
        assertFalse(Mp4Check.isComplete(cut))
        val noMoov = File(d, "c.mov").apply { writeBytes(box("ftyp", 16) + box("mdat", 100)) }
        assertFalse(Mp4Check.isComplete(noMoov))
    }

    @Test fun unknown_size_big_video_path() {
        val root = tempDir(); val ledger = MemoryLedger()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        val p = DeviceProfile("ip", "Mom", "iPhone")
        val full = box("ftyp", 16) + box("mdat", 70_000) + box("moov", 500)
        val r = eng.ingest(p, "big", "IMG_1.MOV", UNKNOWN_SIZE, 0, null) { it.writeBytes(full) }
        assertEquals(full.size.toLong(), (r as IngestResult.Stored).item.size)
        assertFailsWith<VerifyFailed> { eng.ingest(p, "big2", "IMG_2.MOV", UNKNOWN_SIZE, 0, null) { it.writeBytes(full.copyOf(5000)) } }
        assertFailsWith<VerifyFailed> { eng.ingest(p, "big3", "IMG_3.JPG", UNKNOWN_SIZE, 0, null) { it.writeBytes(full) } }
    }
}

class NoNaggingTest {
    private val mom = DeviceProfile("dev-iphone", "Mom", "iPhone 13")

    /** Google never frees the last file (e.g. not backed up): PixelFerry must stop opening Google Photos. */
    @Test fun final_wave_gives_up_and_stops_auto_opening_photos() {
        var t = 0L; val ledger = MemoryLedger(); val root = tempDir()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        eng.ingest(mom, "a", "a.jpg", 10, 0, null) { it.writeBytes(ByteArray(10)) }
        val calls = mutableListOf<Boolean>()
        val wave = WaveCoordinator(eng, BackupTracker(ledger, { t }),
            object : BackupSignals { override fun backingUp() = false; override fun lastActivityMs() = 0L },
            object : FreeUpAction { override fun requestFreeUp(auto: Boolean): Boolean { calls += auto; return auto } },
            object : WaveNotifier {
                override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {}
                override fun stuck(reason: String) {}
                override fun resumed() {}
            }, { t }, settleMs = 0)
        wave.beginFinal { ledger.byState(ItemState.VERIFIED).size }
        var done = false
        for (i in 0 until 400) { t += 60_000; if (wave.tick()) { done = true; break } }   // ~6.6 hours of ticks
        assertTrue(done, "final wave ends by itself")
        assertTrue(wave.gaveUp, "reports that Google didn't free the rest")
        assertTrue(calls.size <= 4, "a handful of tries, not one every 12 minutes forever: ${calls.size}")
        assertEquals(listOf(true, true), calls.take(2))
        assertTrue(calls.drop(2).all { !it }, "after 2 fruitless tries it only notifies, never auto-opens: $calls")
        assertTrue(t < 4 * 3600_000L, "gives up within a few hours")
    }
}

class SafetyRegressionTest {
    private val mom = DeviceProfile("dev-iphone", "Mom", "iPhone 13")

    @Test fun vanished_before_backup_is_sent_again_not_skipped() {
        val root = tempDir(); val ledger = MemoryLedger()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        val data = byteArrayOf(9, 8, 7, 6)
        val a = (eng.ingest(mom, "k", "a.jpg", 4, 0, null) { it.writeBytes(data) } as IngestResult.Stored).item
        // the same bytes also arrive from the Oppo: duplicate
        val oppo = DeviceProfile("oppo", "S", "Oppo")
        assertIs<IngestResult.Duplicate>(eng.ingest(oppo, "o", "same.jpg", 4, 0, null) { it.writeBytes(data) })
        // someone deletes the Pixel copy before Google backed it up
        File(a.pixelPath!!).delete()
        BackupTracker(ledger).reconcile()
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(a.id)!!.state)
        assertNull(eng.alreadyHave(mom.deviceKey, "k"), "must be re-sent")
        assertNull(eng.alreadyHave(oppo.deviceKey, "o"), "its duplicate must be re-sent too")
        val again = eng.ingest(mom, "k", "a.jpg", 4, 0, null) { it.writeBytes(data) }
        assertIs<IngestResult.Stored>(again, "not mistaken for a duplicate of the lost copy")
        assertEquals(ItemState.VERIFIED, ledger.find(mom.deviceKey, "k")!!.state)
    }

    @Test fun window_only_counts_files_present_when_it_opened_and_needs_readable_storage() {
        var t = 0L; val root = tempDir(); val ledger = MemoryLedger()
        val eng = IngestEngine(FakeStorage(root), ledger, reserveBytes = 0)
        var readable = true
        val tracker = BackupTracker(ledger, { t }, storageReadable = { readable })
        val a = (eng.ingest(mom, "a", "a.jpg", 1, 0, null) { it.writeBytes(byteArrayOf(1)) } as IngestResult.Stored).item
        tracker.openFreeUpWindow()
        val b = (eng.ingest(mom, "b", "b.jpg", 1, 0, null) { it.writeBytes(byteArrayOf(2)) } as IngestResult.Stored).item
        // storage unmounted: everything "missing" -> must change nothing
        readable = false
        File(a.pixelPath!!).delete(); File(b.pixelPath!!).delete()
        tracker.reconcile()
        assertEquals(ItemState.VERIFIED, ledger.get(a.id)!!.state)
        readable = true
        tracker.reconcile()
        assertEquals(ItemState.BACKED_UP, ledger.get(a.id)!!.state)
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(b.id)!!.state, "arrived after Free up started: not proven")
        t += 11 * 60_000
        assertFalse(tracker.isWindowOpen(), "window is short")
    }

    @Test fun window_is_never_restored_after_restart() {
        val tracker = BackupTracker(MemoryLedger())
        tracker.restoreWindow(System.currentTimeMillis() + 600_000)
        assertFalse(tracker.isWindowOpen())
    }

    @Test fun parallel_transfers_cannot_overrun_the_reserve() {
        val root = tempDir()
        val eng = IngestEngine(FakeStorage(root, capacity = 1_000_000), MemoryLedger(), reserveBytes = 200_000)
        val gate = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        val t = Thread {
            eng.ingest(mom, "x", "x.jpg", 500_000, 0, null) { started.countDown(); gate.await(); it.writeBytes(ByteArray(500_000)) }
        }.apply { start() }
        started.await()
        // 500k promised to x; 400k more would leave < 200k reserve
        assertFalse(eng.hasRoomFor(400_000))
        assertFailsWith<NotEnoughSpace> { eng.ingest(mom, "y", "y.jpg", 400_000, 0, null) { it.writeBytes(ByteArray(400_000)) } }
        gate.countDown(); t.join()
        assertTrue(eng.hasRoomFor(250_000))
    }
}

class Round2SafetyTest {
    private val mom = DeviceProfile("dev-iphone", "Mom", "iPhone 13")

    private fun setup(): Triple<IngestEngine, MemoryLedger, BackupTracker> {
        val root = tempDir(); val ledger = MemoryLedger()
        val storage = FakeStorage(root, capacity = 10_000_000)
        val eng = IngestEngine(storage, ledger, reserveBytes = 0)
        val tracker = BackupTracker(ledger, freeBytes = storage::freeBytes)
        eng.onStored = { b, at -> tracker.noteStored(b, at) }
        return Triple(eng, ledger, tracker)
    }

    private fun IngestEngine.put(key: String, n: Int, seed: Int) =
        (ingest(mom, key, "$key.jpg", n.toLong(), 0, null) { it.writeBytes(ByteArray(n) { b -> (b * seed).toByte() }) } as IngestResult.Stored).item

    @Test fun deleted_between_waves_never_counts_as_backed_up() {
        val (eng, ledger, tracker) = setup()
        val a = eng.put("a", 5000, 3)
        File(a.pixelPath!!).delete()     // gone BEFORE any free-up
        tracker.openFreeUpWindow()
        tracker.reconcile()
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(a.id)!!.state)
    }

    @Test fun trashed_inside_window_without_freeing_space_is_not_backed_up() {
        val (eng, ledger, tracker) = setup()
        val a = eng.put("a", 50_000, 5)
        val trash = File(tempDir(), "trash").apply { mkdirs() }
        tracker.openFreeUpWindow()
        // user trashes it in a gallery: moved on the same storage -> FakeStorage space would NOT change
        // (simulate by writing an equal-size file inside the Pixel root while removing the original)
        val bin = File(File(a.pixelPath!!).parentFile.parentFile, ".trashed-a.jpg"); File(a.pixelPath!!).renameTo(bin)
        tracker.reconcile()
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(a.id)!!.state, "no space came back -> not Google")
        assertTrue(trash.exists())
    }

    @Test fun real_free_up_is_accepted_even_while_new_files_arrive() {
        val (eng, ledger, tracker) = setup()
        val a = eng.put("a", 80_000, 7)
        tracker.openFreeUpWindow()
        val b = eng.put("b", 60_000, 9)   // a Wi-Fi upload lands during the window
        File(a.pixelPath!!).delete()      // Google frees a
        tracker.reconcile()
        assertEquals(ItemState.BACKED_UP, ledger.get(a.id)!!.state)
        assertEquals(ItemState.VERIFIED, ledger.get(b.id)!!.state)
    }


    @Test fun file_received_before_window_but_committed_after_cannot_fake_freed_space() {
        val (eng, ledger, tracker) = setup()
        val a = eng.put("a", 100_000, 3)                       // on the Pixel, not backed up
        // a 5x bigger file is already received (on disk) but still waiting in the pipeline
        val pending = eng.receive(mom, "big", "big.jpg", 500_000, 0) { it.writeBytes(ByteArray(500_000) { 1 }); null }!!
        Thread.sleep(5)
        tracker.openFreeUpWindow()
        // the user trashes "a" in a gallery app: moved on the same storage, no space comes back
        File(a.pixelPath!!).renameTo(File(File(a.pixelPath!!).parentFile.parentFile, ".trashed-a.jpg"))
        eng.commit(pending, null)                               // pipeline commits the big file now
        tracker.reconcile()
        assertEquals(ItemState.MISSING_UNKNOWN, ledger.get(a.id)!!.state, "old bytes must not count as freed space")
    }
}
