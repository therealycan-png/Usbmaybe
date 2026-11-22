package app.pixelferry.core

import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

/**
 * Real end-to-end: desktop/pixelferry_send.py -> TLS -> ReceiverServer -> IngestEngine.
 * Includes: pinned certificate, wrong code, Pixel full -> wave wait -> Google frees -> resume,
 * resume after re-run, duplicate skip, and the double-confirm delete on the computer.
 */
class DesktopSenderE2ETest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "desktop/pixelferry_send.py").exists() }
    private val script = File(repo, "desktop/pixelferry_send.py")

    private fun sslContext(dir: File): Pair<SSLContext, ByteArray> {
        val ks = File(dir, "ks.p12")
        val p = ProcessBuilder("keytool", "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1", "-storetype", "PKCS12",
            "-keystore", ks.path, "-storepass", "changeit", "-alias", "pf", "-dname", "CN=PixelFerry", "-validity", "3650")
            .redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return ctx to store.getCertificate("pf").encoded
    }

    private fun py(home: File, vararg args: String, input: String = ""): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("python3", script.path) + args).redirectErrorStream(true)
        pb.environment()["HOME"] = home.path
        pb.environment()["USERPROFILE"] = home.path
        pb.environment()["PIXELFERRY_POLL_SECONDS"] = "0.3"
        pb.environment()["PIXELFERRY_DISCOVERY_ADDR"] = "127.0.0.1"
        pb.environment()["PIXELFERRY_DISCOVERY_PORT"] = udpPort.toString()
        val proc = pb.start()
        proc.outputStream.use { it.write(input.toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS))
        println(out)
        return proc.exitValue() to out
    }

    /** Runs the sender with no arguments (double-click mode). Port goes through an env var for the test. */
    private fun pyGuided(home: File, port: String, input: String): Pair<Int, String> {
        val pb = ProcessBuilder("python3", script.path).redirectErrorStream(true)
        pb.environment()["HOME"] = home.path
        pb.environment()["PIXELFERRY_PORT"] = port
        pb.environment()["PIXELFERRY_DISCOVERY_ADDR"] = "127.0.0.1"
        pb.environment()["PIXELFERRY_DISCOVERY_PORT"] = udpPort.toString()
        pb.environment()["DISPLAY"] = ""
        val proc = pb.start()
        proc.outputStream.use { it.write(input.toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS)); println(out)
        return proc.exitValue() to out
    }

    private val udpPort = 18445 + (System.nanoTime() % 500).toInt()

    @Test fun full_flow() {
        val work = tempDir()
        val pixelRoot = File(work, "pixel").apply { mkdirs() }
        val pcPhotos = File(work, "pc/Photos/2024").apply { mkdirs() }
        val home = File(work, "home").apply { mkdirs() }
        val rnd = Random(7)
        val originals = (1..12).associate { i ->
            val name = if (i == 12) "VID_$i.insv" else "IMG_$i.jpg"
            val f = File(pcPhotos, name); f.writeBytes(rnd.nextBytes(200_000 + i * 1000)); f.setLastModified(1_700_000_000_000 + i * 1000L)
            name to f.readBytes()
        }
        File(pcPhotos, "notes.txt").writeText("not media")
        File(pcPhotos, "IMG_1_copy.jpg").writeBytes(originals["IMG_1.jpg"]!!) // duplicate content

        // Pixel holds ~6 files before it is "full"
        val storage = FakeStorage(pixelRoot, capacity = 1_600_000)
        val ledger = MemoryLedger()
        val engine = IngestEngine(storage, ledger, reserveBytes = 300_000)
        val tracker = BackupTracker(ledger)
        val needSpace = java.util.concurrent.atomic.AtomicInteger(0)
        // Simulated Google Photos: when the receiver says it needs space, it "backs up" and frees, inside the window.
        val google = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                if (needSpace.get() > 0) {
                    tracker.openFreeUpWindow()
                    ledger.byState(ItemState.VERIFIED).forEach { File(it.pixelPath!!).delete() }
                    tracker.reconcile()
                    needSpace.set(0)
                }
            }
        }.apply { isDaemon = true; start() }

        val (ctx, der) = sslContext(work)
        val token = Pairing.newToken()
        val code = Pairing.code(token, Pairing.certPin(der))
        val computerKeys = java.util.concurrent.CopyOnWriteArraySet<String>()
        val server = ReceiverServer(engine, ledger, ctx, token, object : ReceiverServer.Listener {
            override fun onNeedSpace(bytes: Long) { needSpace.incrementAndGet() }
            override fun log(msg: String) = println("server: $msg")
        }, port = 0, trustedTokens = { computerKeys }, onPaired = { computerKeys += it })
        server.start()
        val port = server.localPort.toString()
        val discovery = Discovery({ server.localPort }, Pairing.certPin(der), udpPort).also { it.start() }
        try {
            // wrong code is refused
            val bad = Pairing.code(Pairing.newToken(), Pairing.certPin(der))
            val (badExit, badOut) = py(home, "send", pcPhotos.path, "--to", "127.0.0.1", "--port", port, "--code", bad, "--owner", "Shawn", "--device", "Laptop")
            assertNotEquals(0, badExit); assertTrue(badOut.contains("didn't accept the code"))
            // wrong certificate pin (someone pretending to be the Pixel) is refused before any data/token is sent
            val (pinExit, pinOut) = py(home, "send", pcPhotos.path, "--to", "127.0.0.1", "--port", port, "--code", Pairing.code(token, "Z".repeat(16)), "--owner", "S", "--device", "L")
            assertNotEquals(0, pinExit); assertTrue(pinOut.contains("NOT your Pixel"))
            assertTrue(ledger.byState(ItemState.VERIFIED).isEmpty())

            // first time: no address typed (found automatically), code typed once
            val (exit, out) = py(home, "send", pcPhotos.path, "--code", code, "--owner", "Shawn", "--device", "Laptop")
            assertTrue(out.contains("Found your Pixel at 127.0.0.1"), out)
            assertEquals(1, computerKeys.size, "computer got its own key")
            assertEquals(0, exit, out)
            assertTrue(out.contains("Pixel is full"), "a wave pause happened")
            assertTrue(out.contains("Sent 12, duplicates skipped 1, failed 0"), out)

            // every file reached the Pixel byte-identical (either still there, or freed after "backup")
            val all = ledger.byState(ItemState.VERIFIED) + ledger.byState(ItemState.BACKED_UP)
            assertEquals(12, all.size)
            for (it in all) {
                assertEquals(Hashing.sha256(originals[it.originalName]!!.inputStream()), it.sha256, it.originalName)
                assertTrue(File(it.pixelPath!!).name.startsWith("Shawn_Laptop_"))
            }
            assertTrue(all.any { it.pixelPath!!.endsWith("Shawn_Laptop_VID_12_insv.mp4") })

            // re-run = nothing re-sent
            // later: no address, no code
            val (_, again) = py(home, "send", pcPhotos.path, "--owner", "Shawn", "--device", "Laptop")
            assertTrue(again.contains("To send now: 0"), again)

            // "Forget paired computers" on the Pixel: the old key stops working, the sender says so and recovers with the code
            computerKeys.clear()
            val (fExit, forgot) = py(home, "send", pcPhotos.path, "--owner", "Shawn", "--device", "Laptop")
            assertNotEquals(0, fExit); assertTrue(forgot.contains("removed from the Pixel"), forgot)
            assertFalse(File(home, ".pixelferry/settings.json").readText().contains(Pairing.certPin(der)), "stale key forgotten")
            val (_, repaired) = py(home, "send", pcPhotos.path, "--code", code, "--owner", "Shawn", "--device", "Laptop")
            assertTrue(repaired.contains("To send now: 0"), repaired)

            // a new photo that Google has NOT backed up yet
            File(pcPhotos, "IMG_new.jpg").writeBytes(rnd.nextBytes(50_000))
            // double-click ("guided") mode: answers typed in, no command line needed
            // double-click mode, already paired: just the folder + two Enter presses (remembered answers)
            val (_, third) = pyGuided(home, port, "${pcPhotos.path}\n\n\nno\n\n")
            assertTrue(third.contains("already paired, no code needed"), third)
            assertTrue(third.contains("Sent 1,"), third)
            assertTrue(File(home, ".pixelferry/settings.json").readText().contains("Shawn"), "answers remembered")
            assertEquals(ItemState.VERIFIED, ledger.find(ledger.byState(ItemState.VERIFIED).single().deviceKey, ledger.byState(ItemState.VERIFIED).single().itemKey)!!.state)

            // delete: cancelled at second confirmation -> nothing deleted
            val (_, c1) = py(home, "delete-backed-up", pcPhotos.path, input = "DELETE\nno\n")
            assertTrue(c1.contains("Cancelled")); assertEquals(15, pcPhotos.listFiles()!!.size)

            // only BACKED_UP ones (and their duplicates) are offered; still-on-Pixel ones are kept
            val backed = ledger.byState(ItemState.BACKED_UP).size
            assertEquals(12, backed)
            val (_, c2) = py(home, "delete-backed-up", pcPhotos.path, input = "DELETE\nyes\n")
            assertTrue(c2.contains("Deleted"), c2)
            val left = pcPhotos.listFiles()!!.map { it.name }.toSet()
            assertTrue("notes.txt" in left)
            for (it in ledger.byState(ItemState.VERIFIED)) assertTrue(it.originalName in left, "not-yet-backed-up ${it.originalName} must be kept")
            assertTrue("IMG_new.jpg" in left, "not backed up yet -> kept")
            assertEquals(setOf("notes.txt", "IMG_new.jpg"), left)
            assertEquals(13, ledger.byState(ItemState.SOURCE_DELETED).size)
        } finally {
            server.stop(); discovery.stop(); google.interrupt()
        }
    }
}
