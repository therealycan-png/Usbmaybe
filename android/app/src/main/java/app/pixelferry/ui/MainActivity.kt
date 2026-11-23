package app.pixelferry.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.pixelferry.Hub
import app.pixelferry.Phase
import app.pixelferry.GooglePhotos
import app.pixelferry.TransferService
import app.pixelferry.TransferService.Companion.fmt
import app.pixelferry.core.Item
import app.pixelferry.core.ItemState
import app.pixelferry.core.WaveCoordinator
import app.pixelferry.sources.MtpSource
import app.pixelferry.sources.SafSource
import kotlin.concurrent.thread

/**
 * Home: what's happening now (big progress ring), where to send from (big tiles),
 * and what can be cleaned up (delete card). Everything else is one tap away.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private lateinit var root: LinearLayout
    private lateinit var heroBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var runBox: LinearLayout
    private lateinit var deleteBox: LinearLayout
    private lateinit var computerBox: LinearLayout
    private lateinit var sourcesBox: LinearLayout

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION") val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openUsb(d)
            else toast("USB access was not allowed")
        }
    }
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { renderSources() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.statusBarColor = C.PRIMARY
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        heroBox = LinearLayout(this); setupBox = vbox(0); runBox = vbox(0); deleteBox = vbox(0); computerBox = vbox(0); sourcesBox = vbox(0)
        root = vbox(0).apply {
            setBackgroundColor(C.BG); setPadding(0, 0, 0, dp(24))
            add(heroBox, setupBox, runBox, resumeBox, deleteBox, computerBox, sourcesBox, findBox, helpTile())
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(C.BG); addView(root) })
        registerReceiver(permReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerReceiver(attachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
        if (savedInstanceState == null) {
            handleIntent(intent)
            if (!getSharedPreferences("pf", 0).getBoolean("setupDone", false) && SetupActivity.remaining(this) > 0)
                startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onDestroy() { unregisterReceiver(permReceiver); unregisterReceiver(attachReceiver); super.onDestroy() }
    override fun onResume() { super.onResume(); renderAll(); tick() }
    override fun onPause() { ui.removeCallbacksAndMessages(null); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun renderAll() { renderHero(); renderSetup(); renderSources(); renderComputer(); renderDelete(); renderFind() }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                @Suppress("DEPRECATION") val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (d != null) requestUsb(d) // first time: Android asks once, then it opens by itself
            }
            ACTION_FREE_UP_NOW -> freeUpNow()
        }
        setIntent(Intent(this, MainActivity::class.java)) // never re-handle on rotation
    }

    // ---------------- hero ----------------
    private fun renderHero() {
        heroBox.removeAllViews()
        val free = try { fmt(Hub.storage.freeBytes()) } catch (_: Exception) { "?" }
        heroBox.addView(hero("PixelFerry", "Full-quality photos into Google Photos", "⛴️").apply {
            addView(chip("$free free on this Pixel", android.graphics.Color.WHITE, 0x40000000).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    // ---------------- setup banner ----------------
    private fun renderSetup() {
        setupBox.removeAllViews()
        val left = SetupActivity.remaining(this)
        if (left == 0 || Hub.run.active) return
        setupBox.addView(tile("🧭", C.TINT_AMBER, "Finish setup", "$left quick step${if (left > 1) "s" else ""} left, about a minute", highlight = true) {
            startActivity(Intent(this, SetupActivity::class.java))
        })
    }

    // ---------------- live run card ----------------
    private val ring by lazy { ProgressRing(this) }
    private val runTitle by lazy { h("") }
    private val runChip by lazy { chip("", C.PRIMARY, C.TINT_BLUE) }
    private val runDetail by lazy { p() }
    private val stats by lazy { statRow("" to "files", "" to "speed", "" to "time left") }
    private var filesLabel: android.widget.TextView? = null
    private val freeBtn by lazy { btn("Free up space (opens Google Photos)", primary = true) { freeUpNow() } }
    private val stopBtn by lazy { link("Stop", C.DANGER) { confirmStop() } }
    private val detailsLink by lazy { link("Show details", C.INK2) {
        AlertDialog.Builder(this).setTitle("Details").setMessage(Hub.run.errors.takeLast(20).joinToString("\n\n")).setPositiveButton("OK", null).show()
    } }
    private val resumeBtn by lazy { btn("Resume", primary = true) { resume() } }
    private val seeBtn by lazy { btn("See them in Google Photos") { seeInPhotos(Hub.run.tag) } }

    private fun resume() {
        val src = Hub.source
        if (src != null && src.isConnected()) startActivity(Intent(this, PickActivity::class.java).putExtra(PickActivity.EXTRA_AUTO_RESUME, true))
        else AlertDialog.Builder(this).setTitle("Plug it back in")
            .setMessage("Plug ${Hub.run.deviceLabel.ifEmpty { "the device" }} back into the Pixel and unlock it. PixelFerry opens it and continues where it stopped. Nothing is copied twice.")
            .setPositiveButton("OK") { _, _ -> getSharedPreferences("pf", 0).edit().putLong("autoResumeAt", System.currentTimeMillis()).apply() }.show()
    }

    /** Opens a Google Photos search for the file tag (e.g. Mom_iPhone13). */
    private fun seeInPhotos(tag: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/search/" + Uri.encode(tag)))
        try { startActivity(i.setPackage(GooglePhotos.PKG)) } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, i.data)) } catch (_: Exception) { toast("In Google Photos, search $tag") }
        }
    }

    /** The 3 steps, always visible while the Pixel is full. */
    private val freeHelp by lazy {
        text(FREE_STEPS, 15f, C.INK).apply {
            background = rounded(C.TINT_AMBER, dpf(14f)); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }
    private val continueBtn by lazy { btn("Continue") { continueAfterFreeUp() } }
    private val runCard by lazy {
        card().add(runChip, runTitle, centered(ring).apply { setPadding(0, dp(12), 0, dp(4)) }, stats,
            runDetail.apply { gravity = Gravity.CENTER_HORIZONTAL }, batteryCard, resumeBtn, seeBtn, freeHelp, freeBtn, continueBtn, folderTip, detailsLink, stopBtn)
    }
    /** Google backs up a new device folder only after one switch in Google Photos: ask right away, not hours later. */
    private val folderTip by lazy {
        vbox(0).apply {
            background = rounded(C.TINT_BLUE, dpf(14f)); setPadding(dp(14), dp(12), dp(14), dp(6))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
            addView(text("One tap so Google backs these up", 16f, C.INK, bold = true))
            addView(text("1. Open Google Photos\n2. Tap your profile picture\n3. Photos settings › Backup\n4. Back up device folders\n5. Turn on the PixelFerry folder", 15f, C.INK))
            addView(link("Open Google Photos") { GooglePhotos.open(this@MainActivity) })
            addView(link("It's on", C.INK2) { getSharedPreferences("pf", 0).edit().putBoolean("folderOk_${Hub.run.deviceLabel}", true).apply(); visibility = View.GONE })
        }
    }

    private fun tick() {
        val r = Hub.run
        val showRun = r.active || r.total > 0
        if (showRun && runCard.parent == null) runBox.addView(runCard)
        if (!showRun && runCard.parent != null) runBox.removeView(runCard)
        if (showRun) {
            val waiting = r.active && (r.phase == Phase.WAITING_GOOGLE || Hub.wave.phase == WaveCoordinator.Phase.WAITING)
            val (chipText, fg, bg) = when (r.phase) {
                Phase.DONE, Phase.DELETE_DONE -> Triple("✓  Finished", C.SUCCESS, C.TINT_GREEN)
                Phase.DONE_PENDING_GOOGLE, Phase.FINISHING -> Triple("☁  Google is backing up", C.PRIMARY, C.TINT_BLUE)
                Phase.WAITING_GOOGLE -> Triple("⏸  Pixel full: paused", C.WARN, C.TINT_AMBER)
                Phase.UNPLUGGED -> Triple("🔌  Plug back in", C.WARN, C.TINT_AMBER)
                Phase.LOW_BATTERY -> Triple("🔋  Charging break", C.WARN, C.TINT_AMBER)
                Phase.STOPPED -> Triple("■  Stopped", C.INK2, C.LINE)
                Phase.ERROR -> Triple("!  Needs a look", C.DANGER, C.TINT_RED)
                Phase.DELETING -> Triple("🧹  Freeing space", C.PRIMARY, C.TINT_BLUE)
                Phase.RECEIVING -> Triple("💻  Computer", C.PRIMARY2, C.TINT_VIOLET)
                else -> Triple("●  Copying", C.PRIMARY, C.TINT_BLUE)
            }
            runChip.text = chipText; runChip.setTextColor(fg); runChip.background = rounded(bg, dpf(14f))
            runTitle.text = r.title
            runDetail.text = r.detail + if (r.failed > 0) "\n⚠ %,d file(s) couldn't be copied and were left on the device.".format(r.failed) else ""
            detailsLink.visibility = if (r.errors.isNotEmpty()) View.VISIBLE else View.GONE
            freeHelp.visibility = if (waiting) View.VISIBLE else View.GONE
            resumeBtn.visibility = if (!r.active && r.phase in setOf(Phase.UNPLUGGED, Phase.STOPPED, Phase.ERROR) && r.total > r.done) View.VISIBLE else View.GONE
            resumeBtn.text = "Resume · %,d left".format((r.total - r.done).coerceAtLeast(0))
            seeBtn.visibility = if (!r.active && r.phase in setOf(Phase.DONE, Phase.DONE_PENDING_GOOGLE) && r.tag.isNotEmpty()) View.VISIBLE else View.GONE
            folderTip.visibility = if (r.active && !waiting && r.phase in setOf(Phase.COPYING, Phase.FINISHING) && r.done > 0 &&
                (r.done - r.failed - r.skipped) > 0 &&
                !getSharedPreferences("pf", 0).getBoolean("folderOk_${r.deviceLabel}", false)) View.VISIBLE else View.GONE
            val frac = if (r.bytesTotal > 0) r.bytesDone.toFloat() / r.bytesTotal else 0f
            ring.indeterminate = r.active && r.total == 0
            ring.fraction = frac
            ring.center = "${(frac * 100).toInt()}%"
            ring.caption = if (r.active) "${fmt(r.bytesDone)} of ${fmt(r.bytesTotal)}" else ""
            val speed = speedNow(r)
            val left = if (speed > 0 && r.active && !waiting) (r.bytesTotal - r.bytesDone) / speed else -1
            setStat("files", "%,d".format(r.done))
            (stats.findViewWithTag<android.widget.TextView>("files")?.parent as? LinearLayout)?.getChildAt(1)
                ?.let { (it as android.widget.TextView).text = "of %,d files".format(r.total) }
            setStat("speed", if (r.active) "${fmt(speed)}/s" else fmt(r.bytesDone))
            (stats.findViewWithTag<android.widget.TextView>("speed")?.parent as? LinearLayout)?.getChildAt(1)
                ?.let { (it as android.widget.TextView).text = if (r.active) "speed" else "copied" }
            val took = (System.currentTimeMillis() - r.startedAt) / 1000
            (stats.findViewWithTag<android.widget.TextView>("time left")?.parent as? LinearLayout)?.getChildAt(1)
                ?.let { (it as android.widget.TextView).text = if (r.active) "time left" else "took" }
            (stats.findViewWithTag<android.widget.TextView>("time left")?.parent as? LinearLayout)?.getChildAt(1)
                ?.let { if (waiting) (it as android.widget.TextView).text = "paused" }
            setStat("time left", when { !r.active && r.startedAt > 0 -> dur(took)
                waiting -> dur(((System.currentTimeMillis() - r.waitStartedAt) / 1000).coerceAtLeast(0)); left < 0 -> "–"; left < 60 -> "<1 min"; left < 3600 -> "${left / 60} min"; else -> "${left / 3600} h ${left % 3600 / 60} m" })
            freeBtn.visibility = if (waiting) View.VISIBLE else View.GONE
            continueBtn.visibility = if (waiting) View.VISIBLE else View.GONE
            stopBtn.visibility = if (r.active) View.VISIBLE else View.GONE
        }
        val (level, charging) = battery()
        batteryCard.visibility = if (r.active && !charging && Hub.source is MtpSource) View.VISIBLE else View.GONE
        batteryText.text = "Pixel battery $level% and dropping: the Pixel is powering the other device through the cable."
        if (!r.active && runCard.tag != "idle") { runCard.tag = "idle"; renderDelete() }
        if (r.active) runCard.tag = null
        ui.postDelayed({ tick() }, 1000)
    }

    private fun dur(sec: Long) = when { sec < 60 -> "<1 min"; sec < 3600 -> "${sec / 60} min"; else -> "${sec / 3600} h ${sec % 3600 / 60} m" }


    /** Copy speed over the last minute of actual copying (skipped files and waiting don't distort it). */
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private fun speedNow(r: app.pixelferry.RunState): Long {
        val now = System.currentTimeMillis()
        if (!r.active || r.phase != Phase.COPYING) { samples.clear(); return 0 }
        if (samples.isNotEmpty() && r.bytesCopied < samples.last().second) samples.clear() // new run
        samples.addLast(now to r.bytesCopied)
        while (samples.size > 2 && now - samples.first().first > 60_000) samples.removeFirst()
        val (t0, b0) = samples.first()
        return if (now - t0 >= 3_000) (r.bytesCopied - b0) * 1000 / (now - t0) else 0
    }

    private var batteryCache = 100 to true
    private var batteryAt = 0L
    private fun battery(): Pair<Int, Boolean> {
        if (System.currentTimeMillis() - batteryAt < 30_000) return batteryCache
        batteryAt = System.currentTimeMillis()
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100 to true
        val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        batteryCache = level to (b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
        return batteryCache
    }

    private val batteryText by lazy { text("", 15f, C.INK) }
    /** Shown while the Pixel drains because it's feeding the other device. */
    private val batteryCard by lazy {
        vbox(0).apply {
            background = rounded(C.TINT_AMBER, dpf(14f)); setPadding(dp(14), dp(12), dp(14), dp(6))
            addView(text("🔋 Stop the Pixel powering the other device", 16f, C.INK, bold = true))
            addView(batteryText)
            addView(text("Try one of these (the copy keeps going):\n" +
                "1. On the Pixel: swipe down › tap the USB notification › Power options › turn OFF \"Charge connected device\".\n" +
                "2. Or on the other phone/tablet: swipe down › tap its USB notification › choose to power or charge the connected device.\n" +
                "3. Or use a USB-C hub with a charger port (\"PD pass-through\"): Pixel › hub, other device › hub, charger › hub.\n" +
                "If the Pixel gets to 15%, PixelFerry pauses safely and continues later. Nothing is lost.", 14.5f, C.INK))
            addView(link("Open USB settings") { openUsbSettings() })
            visibility = View.GONE
        }
    }

    private fun openUsbSettings() {
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$UsbDetailsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$ConnectedDeviceDashboardActivity"),
            Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in tries) { try { startActivity(i); toast("Find USB › Power options"); return } catch (_: Exception) {} }
    }

    private fun setStat(label: String, value: String) { (stats.findViewWithTag<android.widget.TextView>(label))?.text = value }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("Stop the transfer?")
            .setMessage("Everything copied so far is kept. Later, open the device again and tap Resume to carry on.")
            .setNegativeButton("Keep going", null)
            .setPositiveButton("Stop") { _, _ -> TransferService.send(this, TransferService.ACTION_STOP) }.show()
    }

    /** Step 1: open the free-up window and Google Photos. */
    private fun freeUpNow() {
        Hub.startFreeUp(this)
        toast("In Google Photos: wait until backup is done, then profile picture › Free up space › Free up")
    }

    /** Step 3: back from Google Photos. Credit what Google freed; a paused transfer continues by itself. */
    private fun continueAfterFreeUp() {
        thread {
            val n = Hub.finishFreeUp(this)
            val gone = Hub.unclaimedGone()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    gone.isNotEmpty() -> askAboutGone(gone, n)
                    n > 0 -> toast("%,d photos confirmed in Google Photos".format(n))
                    else -> toast("Nothing has left the Pixel yet. In Google Photos, run Free up space once backup is done.")
                }
                renderDelete()
            }
        }
    }

    /**
     * Copies left the Pixel while PixelFerry wasn't watching (you freed space in Google Photos first).
     * You decide: count them as backed up (double confirm), or keep them as "send again" (always safe).
     */
    private fun askAboutGone(gone: List<app.pixelferry.core.Item>, confirmed: Int) {
        val n = gone.size
        AlertDialog.Builder(this)
            .setTitle("%,d copies left the Pixel".format(n))
            .setMessage((if (confirmed > 0) "%,d were confirmed automatically.\n\n".format(confirmed) else "") +
                "%,d more disappeared from the Pixel while PixelFerry wasn't watching.\n\n".format(n) +
                "Did Google Photos' Free up space remove them (after it finished backing up)?\n\n" +
                "If you're not sure, choose Re-send. They're copied again next time; Google skips exact duplicates, so nothing is wasted in your account.")
            .setNegativeButton("Re-send later", null)
            .setPositiveButton("Yes, Google freed them") { _, _ ->
                AlertDialog.Builder(this).setTitle("Last check")
                    .setMessage("Count these %,d as safely in Google Photos? Then they can be deleted from the phone they came from.\n\nOnly say yes if Google Photos' own Free up space removed them, not you or another app.".format(n))
                    .setNegativeButton("No", null)
                    .setPositiveButton("Yes") { _, _ ->
                        thread { Hub.claimFreedByGoogle(gone); runOnUiThread { toast("%,d photos confirmed".format(n)); renderDelete() } }
                    }.show()
            }.show()
    }

    // ---------------- sources ----------------
    private fun renderSources() {
        sourcesBox.removeAllViews()
        sourcesBox.addView(section("Send photos from"))
        for (d in usb.deviceList.values) {
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device" }
            val apple = d.vendorId == 1452
            sourcesBox.addView(tile(if (apple) "📱" else "📲", C.TINT_GREEN, name, "Plugged in · tap to choose photos", highlight = true) { requestUsb(d) })
        }
        if (usb.deviceList.isEmpty()) {
            sourcesBox.addView(tile("🔌", C.TINT_BLUE, "A phone, tablet or camera", "Plug it into the Pixel with a cable") { cableHelp() })
        }
        sourcesBox.addView(tile("💻", C.TINT_VIOLET, "A computer", "Windows or Linux, over Wi-Fi") {
            if (TransferService.receiver == null) TransferService.send(this, TransferService.ACTION_RECV_ON)
            ui.postDelayed({ renderComputer() }, 1500)
        })
        sourcesBox.addView(tile("🗂️", C.TINT_AMBER, "A folder, SD card or USB drive", "Pick it with Android's file picker") {
            @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        })
    }

    private fun makeDeletableTile(onPixel: Int) = if (Hub.freeUpInProgress(this))
        tile("✅", C.TINT_GREEN, "Done in Google Photos? Tap to check", "PixelFerry counts what Google freed", highlight = true) { continueAfterFreeUp() }
    else tile("☁️", C.TINT_BLUE, "Make them deletable",
        "%,d copies to confirm. Free them in Google Photos, then you can delete the originals.".format(onPixel)) { makeDeletable() }

    /** The manual free-up, step by step. */
    private fun makeDeletable() {
        // already freed in Google Photos without the button? deal with those first
        thread {
            val gone = Hub.unclaimedGone()
            runOnUiThread { if (!isDestroyed) { if (gone.isNotEmpty()) askAboutGone(gone, 0) else makeDeletableSteps() } }
        }
    }

    private fun makeDeletableSteps() {
        if (Hub.freeUpInProgress(this)) {
            AlertDialog.Builder(this).setTitle("Done in Google Photos?")
                .setMessage("If you ran Free up space in Google Photos, tap Check. PixelFerry counts the photos Google removed as safely backed up.")
                .setPositiveButton("Check") { _, _ -> continueAfterFreeUp() }
                .setNeutralButton("Open Google Photos") { _, _ -> GooglePhotos.open(this) }
                .setNegativeButton("Later", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Make them deletable")
            .setMessage(FREE_STEPS)
            .setPositiveButton("Free up space") { _, _ -> freeUpNow() }
            .setNegativeButton("Later", null).show()
    }

    private fun cableHelp() {
        AlertDialog.Builder(this).setTitle("Plug in by cable")
            .setMessage("📱 iPhone / iPad\nUnlock it and tap Trust or Allow.\nFor full quality once: iPhone Settings › Photos › Transfer to Mac or PC › Keep Originals.\n\n" +
                "📲 Oppo, OnePlus, other Android\nOn that device, tap the USB notification and choose File transfer.\n\n" +
                "📷 Insta360 or camera\nIn its USB settings pick \"Camera\" or \"File transfer\". If it only offers \"Storage\", use the folder option instead.\n\n" +
                "Nothing appears? On this Pixel tap the USB notification › \"USB controlled by: This device\".")
            .setPositiveButton("Got it", null).show()
    }

    private fun requestUsb(d: UsbDevice) {
        if (usb.hasPermission(d)) { openUsb(d); return }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    private fun openUsb(d: UsbDevice) {
        if (Hub.run.active) { toast("A transfer is running. Stop it first or wait."); return }
        val current = Hub.source
        if (current is MtpSource && current.isDevice(d) && current.isConnected()) {
            startActivity(Intent(this, PickActivity::class.java)); return // reuse the open session
        }
        thread {
            current?.close() // close the old session before opening a new one (off the main thread)
            Hub.source = null
            val src = try { MtpSource.open(usb, d) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) { src?.let { Hub.source = it }; return@runOnUiThread }
                if (src == null) {
                    AlertDialog.Builder(this).setTitle("Almost there")
                        .setMessage("The device needs to allow access first:\n\n📱 iPhone: unlock it and tap Trust / Allow.\n📲 Android: tap its USB notification › File transfer.\n📷 Camera: in its USB settings pick Camera or File transfer.\n\nThen tap the device again.")
                        .setPositiveButton("OK", null).show()
                } else {
                    Hub.source = src
                    startActivity(Intent(this, PickActivity::class.java))
                }
            }
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val label = Uri.decode(uri.lastPathSegment ?: "Folder").substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        Hub.source = SafSource(contentResolver, uri, label)
        startActivity(Intent(this, PickActivity::class.java))
    }

    // ---------------- computer ----------------
    private fun renderComputer() {
        computerBox.removeAllViews()
        val code = TransferService.pairingCode
        if (TransferService.receiver == null || code == null) return
        val ip = wifiIp()
        val c = card(C.TINT_VIOLET)
        c.add(chip("●  Ready for your computer", C.PRIMARY2, C.CARD), h("Send from your computer"),
            p("First time: get the sender onto your computer. It already contains this Pixel's code, so there's nothing to type."),
            btn("Send to my computer", primary = true, color = C.PRIMARY2) { app.pixelferry.ShareProvider.shareSender(this, code) },
            text("Use Nearby Share, Google Drive or a USB cable. Some email apps block it. Only send it to your own computer.\nIf Windows says \"Windows protected your PC\": click More info › Run anyway.", 13.5f, C.INK2).apply { setPadding(0, dp(6), 0, dp(10)) },
            p("Then on the computer: extract it and double-click  PixelFerry Send, then choose the photo folder. It finds this Pixel by itself."),
            text("Or type the code by hand if it asks:", 14f, C.INK2).apply { setPadding(0, dp(10), 0, 0) })
        c.add(text("Pairing code", 13f, C.INK2).apply { setPadding(0, dp(8), 0, 0) },
            text(code, 24f, C.PRIMARY2, bold = true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextIsSelectable(true) },
            text("Pixel address (only if it can't find it): $ip", 14f, C.INK2).apply { setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) },
            p("🔒 Encrypted and locked to this Pixel. \"Forget paired computers\" makes a new code.", 14f),
            link("Forget paired computers", C.INK2) {
                TransferService.forgetComputers(this); toast("Computers will need the code again")
            },
            link("Stop receiving", C.DANGER) { TransferService.send(this, TransferService.ACTION_RECV_OFF); ui.postDelayed({ renderComputer() }, 500) })
        computerBox.addView(c)
    }

    private fun wifiIp(): String {
        @Suppress("DEPRECATION") val ip = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
        return if (ip == 0) "Connect the Pixel to Wi-Fi" else "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}.${ip shr 24 and 255}"
    }

    // ---------------- delete from source ----------------
    private fun renderDelete() {
        val src = Hub.source?.takeIf { it.isConnected() }
        if (src == null) {
            thread {
                // a transfer that never finished (device or Pixel died, app restarted): never lose Resume
                val unfinished = if (Hub.run.active) emptyList() else filesDir.listFiles { f -> f.name.startsWith("selection-") }?.mapNotNull { f ->
                    val keys = f.readLines().toSet()
                    val dev = Hub.ledger.devices().firstOrNull { d -> PickActivity.selectionName(d.deviceKey) == f.name } ?: return@mapNotNull null
                    val done = Hub.ledger.forDevice(dev.deviceKey).filter { Hub.ledger.isDone(it) }.map { it.itemKey }.toSet()
                    val left = keys.count { it !in done }
                    if (left > 0) "${dev.owner}'s ${dev.label}" to left else null
                } ?: emptyList()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    resumeBox.removeAllViews()
                    for ((label, left) in unfinished) resumeBox.addView(tile("⏯️", C.TINT_BLUE, "Unfinished: $label",
                        "%,d left. Plug it in and unlock it to continue. Nothing is copied twice.".format(left), highlight = true) {
                        getSharedPreferences("pf", 0).edit().putLong("autoResumeAt", System.currentTimeMillis()).apply(); cableHelp()
                    })
                }
            }
            thread {
                // remind which devices have backed-up photos waiting to be deleted
                val waitingGoogle = Hub.ledger.countState(ItemState.VERIFIED) + Hub.ledger.countState(ItemState.MISSING_UNKNOWN)
                val list = (Hub.ledger.devices()).mapNotNull { d ->
                    val n = Hub.ledger.deletableFor(d.deviceKey).size
                    if (n > 0 && !d.deviceKey.startsWith("pc:")) "${d.owner}'s ${d.label}" to n else null
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    deleteBox.removeAllViews()
                    if (waitingGoogle > 0 && !Hub.run.active) deleteBox.addView(makeDeletableTile(waitingGoogle))
                    for ((label, n) in list) deleteBox.addView(tile("🧹", C.TINT_GREEN, "Delete originals from $label",
                        "%,d are safe in Google Photos. Plug it in to delete them there.".format(n)) { cableHelp() })
                }
            }
            return
        }
        thread { // database work off the main thread (10k rows)
            val prof = Hub.ledger.device(src.deviceKey)
            val label = prof?.let { "${it.owner}'s ${it.label}" } ?: src.suggestedLabel
            val items = Hub.ledger.deletableFor(src.deviceKey)
            val waiting = Hub.ledger.forDevice(src.deviceKey).count { it.state == ItemState.VERIFIED || it.state == ItemState.MISSING_UNKNOWN }
            runOnUiThread { if (!isDestroyed) showDelete(label, items, waiting) }
        }
    }

    private fun showDelete(label: String, items: List<Item>, waiting: Int) {
        deleteBox.removeAllViews()
        if (items.isEmpty() && waiting == 0) return
        val c = card(C.TINT_GREEN)
        if (items.isNotEmpty()) {
            c.add(chip("✓  Safe in Google Photos", C.SUCCESS, C.CARD),
                h("%,d photos & videos backed up".format(items.size)),
                p("They were copied byte for byte and Google Photos has them. You can free ${fmt(items.sumOf { it.size })} on $label."))
        } else {
            c.add(chip("☁  Waiting for Google", C.WARN, C.CARD), h("Almost done"))
        }
        if (waiting > 0) c.addView(p("%,d more are on the Pixel waiting for Google backup. They stay on $label until then.".format(waiting), 15f))
        if (Hub.source?.canDelete == false) {
            c.addView(p("This device has no serial number, so PixelFerry won't delete from it. Delete them on the device itself.", 15f, C.WARN))
        } else if (items.isNotEmpty()) {
            c.addView(btn("Delete originals from $label", primary = true, color = C.DANGER) {
                if (Hub.run.active) toast("Wait until the current transfer finishes, then delete.")
                else confirmDelete(label, items.size, items.sumOf { it.size })
            })
        }
        if (waiting > 0 && !Hub.run.active) c.addView(link("Make the other %,d deletable".format(waiting)) { makeDeletable() })
        deleteBox.addView(c)
    }

    /** Two separate confirmations, always. */
    private fun confirmDelete(label: String, n: Int, bytes: Long) {
        val tick = android.widget.CheckBox(this).apply {
            text = "I checked: Google Photos backup quality says Original quality"; textSize = 15f; setTextColor(C.INK)
            buttonTintList = android.content.res.ColorStateList.valueOf(C.PRIMARY); minHeight = dp(48)
        }
        val box = vbox(20).add(p("These files (${fmt(bytes)}) were copied to the Pixel, checked byte for byte, and Google Photos has backed them up. Files not backed up yet are NOT included.\n\nGoogle must keep them in Original quality, or the only copies left would be smaller ones.", 15f), tick)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Delete %,d originals from $label?".format(n))
            .setView(box)
            .setNeutralButton("Check in Google Photos") { _, _ -> GooglePhotos.open(this) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Last check")
                    .setMessage("Permanently delete %,d files from $label? This can't be undone on $label.\n\n(With iCloud Photos on, iPhone deletions also go to iCloud's Recently Deleted for 30 days.)".format(n))
                    .setNegativeButton("No, keep them", null)
                    .setPositiveButton("Yes, delete") { _, _ ->
                        TransferService.pendingDelete = Hub.source
                        TransferService.send(this, TransferService.ACTION_DELETE)
                    }.show()
            }.create()
        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE); ok.isEnabled = false
            tick.setOnCheckedChangeListener { _, c -> ok.isEnabled = c }
        }
        dlg.show()
    }

    // ---------------- help ----------------
    private val findBox by lazy { vbox(0) }
    private val resumeBox by lazy { vbox(0) }

    /** One tile per person/device already sent, opening a Google Photos search for its tag. */
    private fun renderFind() {
        thread {
            val rows = Hub.ledger.devices().mapNotNull { d ->
                val n = Hub.ledger.forDevice(d.deviceKey).count { it.state != ItemState.MISSING_UNKNOWN }
                if (n > 0) Triple("${d.owner}'s ${d.label}", app.pixelferry.core.Naming.tag(d.owner, d.label), n) else null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                findBox.removeAllViews()
                if (rows.isEmpty()) return@runOnUiThread
                findBox.addView(section("Find photos"))
                for ((label, tag, n) in rows) findBox.addView(tile("🔎", C.TINT_BLUE, label, "%,d sent · search \"$tag\" in Google Photos".format(n)) { seeInPhotos(tag) })
            }
        }
    }

    private fun helpTile(): View = vbox(0).add(section("Help"),
        tile("💡", C.TINT_BLUE, "Tips & questions", "iCloud, battery, Insta360, finding photos") {
            AlertDialog.Builder(this).setTitle("Tips")
                .setMessage("☁ Leaving iCloud\nTurn on \"Download and Keep Originals\" on the iPhone, transfer, delete, repeat until iCloud is empty.\n\n" +
                    "🔎 Finding photos\nEvery file is named like Mom_iPhone13_IMG_1234. In Google Photos, open a photo › ⓘ to see its name, or search the name.\n\n" +
                    "🔋 Battery\nThe other phone draws power from the Pixel. PixelFerry pauses at 15% and continues when it's charging or back at 30%.\n\n" +
                    "🎥 Insta360\nRaw files arrive as NAME_insv.mp4 / NAME_insp.jpg, bytes untouched. Rename back to edit in Insta360 Studio.\n\n" +
                    "🛟 Safety\nNothing is deleted anywhere until Google Photos has it, and you confirm twice.")
                .setPositiveButton("OK", null).show()
        },
        tile("⚙️", C.TINT_AMBER, "Setup", "Permissions and Google Photos settings") { startActivity(Intent(this, SetupActivity::class.java)) })

    companion object {
        const val ACTION_USB_PERMISSION = "app.pixelferry.USB_PERMISSION"
        const val REQ_TREE = 7
        const val ACTION_FREE_UP_NOW = "app.pixelferry.FREE_UP_NOW"
        const val FREE_STEPS = "1. Tap Free up space (opens Google Photos).\n" +
            "2. In Google Photos, wait until backup is finished (the cloud at the top shows it's done).\n" +
            "3. Tap your profile picture › Free up space › Free up.\n" +
            "4. Come back here and tap Continue (or Check).\n\n" +
            "Google only removes photos it has backed up. Only those can be deleted from your phones."
    }
}
