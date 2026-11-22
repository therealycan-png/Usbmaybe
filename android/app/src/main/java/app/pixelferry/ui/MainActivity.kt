package app.pixelferry.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import app.pixelferry.FreeUpService
import app.pixelferry.Hub
import app.pixelferry.PhotosWatcher
import app.pixelferry.TransferService
import app.pixelferry.TransferService.Companion.fmt
import app.pixelferry.core.ItemState
import app.pixelferry.sources.MtpSource
import app.pixelferry.sources.SafSource
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var setup: LinearLayout
    private lateinit var sources: LinearLayout
    private lateinit var computer: LinearLayout
    private lateinit var delete: LinearLayout
    private val usb by lazy { getSystemService(UsbManager::class.java) }

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
        title = "PixelFerry"
        root = vbox().apply { setBackgroundColor(0xFFF1F3F4.toInt()) }
        status = card(); setup = card(); sources = card(); computer = card(); delete = card()
        root.add(status, sources, delete, computer, setup, helpCard())
        setContentView(ScrollView(this).apply { addView(root); isFillViewport = true })
        registerReceiver(permReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerReceiver(attachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleUsbIntent(intent) }

    override fun onDestroy() { unregisterReceiver(permReceiver); unregisterReceiver(attachReceiver); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        renderSetup(); renderSources(); renderComputer(); renderDelete(); tick()
    }

    override fun onPause() { ui.removeCallbacksAndMessages(null); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun handleUsbIntent(i: Intent?) {
        if (i?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION") val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (usb.hasPermission(d)) openUsb(d)
    }

    // ---------- live status ----------
    private val bar by lazy { ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal) }
    private val stTitle by lazy { h("") }
    private val stDetail by lazy { p() }
    private val stCounts by lazy { p(size = 14f, color = 0xFF5F6368.toInt()) }

    private fun tick() {
        val r = Hub.run
        if (status.childCount == 0) {
            status.add(stTitle, bar, stCounts, stDetail,
                btn("Stop") { TransferService.send(this, TransferService.ACTION_STOP) })
        }
        stTitle.text = r.title
        stDetail.text = r.detail + if (r.errors.isNotEmpty()) "\n\nProblems: ${r.errors.takeLast(3).joinToString("\n")}" else ""
        bar.max = r.total.coerceAtLeast(1); bar.progress = r.done
        val el = (System.currentTimeMillis() - r.startedAt).coerceAtLeast(1)
        val speed = if (r.startedAt > 0) r.bytesDone * 1000 / el else 0
        stCounts.text = if (r.total > 0) "${r.done} of ${r.total} · ${fmt(r.bytesDone)} of ${fmt(r.bytesTotal)} · ${fmt(speed)}/s\n" +
            "duplicates ${r.duplicates} · already done ${r.skipped} · failed ${r.failed} · wave ${Hub.wave.waveNumber}"
        else "Free on Pixel: ${fmt(Hub.storage.freeBytes())}"
        if (!r.active && stTitle.tag != "idle") { stTitle.tag = "idle"; renderDelete() }
        if (r.active) stTitle.tag = null
        // keep the screen on during a run so the free-up helper can tap Google Photos
        if (r.active) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.postDelayed({ tick() }, 1000)
    }

    // ---------- setup checklist ----------
    private fun renderSetup() {
        setup.removeAllViews()
        setup.add(h("Setup checklist"))
        val storageOk = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val notifOk = PhotosWatcher.isEnabled(this)
        val a11yOk = FreeUpService.isEnabled(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        fun row(ok: Boolean, text: String, fix: () -> Unit) {
            setup.add(p((if (ok) "✅ " else "⬜ ") + text))
            if (!ok) setup.add(btn("Fix: $text") { fix() })
        }
        row(storageOk, "Save photos to the Pixel") {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        row(notifOk, "See when Google Photos is backing up") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("Turn ON 'PixelFerry backup watcher'")
        }
        row(a11yOk, "Auto-tap 'Free up space' (optional)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Open 'PixelFerry free-up helper' and turn it ON")
        }
        row(batteryOk, "Keep running with the screen off") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        setup.add(p("In Google Photos: Settings > Backup > ON, quality 'Original', and after the first transfer from a new device, " +
            "Back up device folders > turn ON the new PixelFerry folder.", 13f, 0xFF5F6368.toInt()))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderSetup()
    }

    // ---------- sources ----------
    private fun renderSources() {
        sources.removeAllViews()
        sources.add(h("Send from a device"))
        val list = usb.deviceList.values
        if (list.isEmpty()) {
            sources.add(p("Plug a phone, tablet or camera into the Pixel with a cable.\n" +
                "• iPhone: unlock it, tap Trust / Allow.\n• Oppo / OnePlus: pick 'File transfer' in its USB notification.\n" +
                "• If nothing shows: on the Pixel's USB notification choose 'USB controlled by: This device'."))
        }
        for (d in list) {
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device ${d.vendorId}" }
            sources.add(btn("Open $name", primary = true) { requestUsb(d) })
        }
        sources.add(btn("Choose a folder / SD card / USB drive") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        })
    }

    private fun requestUsb(d: UsbDevice) {
        if (usb.hasPermission(d)) { openUsb(d); return }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    private fun openUsb(d: UsbDevice) {
        if (Hub.run.active) { toast("A transfer is running. Stop it first or wait."); return }
        thread {
            val src = try { MtpSource.open(usb, d) } catch (e: Exception) { null }
            runOnUiThread {
                if (src == null) {
                    AlertDialog.Builder(this).setTitle("Can't read this device yet")
                        .setMessage("• iPhone: unlock it and tap 'Trust' or 'Allow'.\n• Android phone/tablet: swipe down on it, tap the USB notification, choose 'File transfer'.\n• Camera: set its USB mode to MTP/PTP or storage. In storage mode use 'Choose a folder'.\nThen tap Open again.")
                        .setPositiveButton("OK", null).show()
                } else {
                    Hub.source?.takeIf { it !== src }?.close()
                    Hub.source = src
                    startActivity(Intent(this, PickActivity::class.java))
                }
            }
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val label = Uri.decode(uri.lastPathSegment ?: "Folder").substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        Hub.source = SafSource(contentResolver, uri, label)
        startActivity(Intent(this, PickActivity::class.java))
    }

    // ---------- delete from source ----------
    private fun renderDelete() {
        delete.removeAllViews()
        val src = Hub.source
        if (src == null) { delete.visibility = android.view.View.GONE; return }
        delete.visibility = android.view.View.VISIBLE
        val prof = Hub.ledger.device(src.deviceKey)
        val label = prof?.let { "${it.owner}'s ${it.label}" } ?: src.suggestedLabel
        val items = Hub.ledger.deletableFor(src.deviceKey)
        val waiting = Hub.ledger.forDevice(src.deviceKey).count { it.state == ItemState.VERIFIED }
        delete.add(h("Delete from $label"))
        delete.add(p("${items.size} files (${fmt(items.sumOf { it.size })}) are confirmed backed up by Google Photos and can be removed from $label." +
            if (waiting > 0) "\n$waiting more are on the Pixel waiting for Google backup (kept on $label for now)." else ""))
        if (items.isNotEmpty()) delete.add(btn("Delete ${items.size} transferred files from $label") { confirmDelete(label, items.size, items.sumOf { it.size }) })
        if (waiting > 0 && !Hub.run.active) delete.add(btn("Finish: get the last $waiting confirmed by Google now") {
            TransferService.send(this, TransferService.ACTION_FINISH)
        })
    }

    /** Two separate confirmations, always. */
    private fun confirmDelete(label: String, n: Int, bytes: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete $n files from $label?")
            .setMessage("These $n files (${fmt(bytes)}) were copied to the Pixel, checked byte for byte, and Google Photos " +
                "has backed them up (it already removed them from the Pixel with Free up space).\n\nFiles not yet backed up are NOT included.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Last check")
                    .setMessage("Permanently delete $n files from $label? This cannot be undone on $label.\n\n" +
                        "(If iCloud Photos is on, iPhone deletions also go to iCloud's 'Recently Deleted' for 30 days.)")
                    .setNegativeButton("No, keep them", null)
                    .setPositiveButton("Yes, delete $n") { _, _ ->
                        TransferService.pendingDelete = Hub.source
                        TransferService.send(this, TransferService.ACTION_DELETE)
                    }.show()
            }.show()
    }

    // ---------- computer ----------
    private fun renderComputer() {
        computer.removeAllViews()
        computer.add(h("Receive from a computer (Wi-Fi)"))
        val on = TransferService.receiver != null
        val sw = Switch(this).apply { text = "Receive from Windows / Linux computer"; isChecked = on; textSize = 16f }
        sw.setOnCheckedChangeListener { _, c ->
            TransferService.send(this, if (c) TransferService.ACTION_RECV_ON else TransferService.ACTION_RECV_OFF)
            ui.postDelayed({ renderComputer() }, 700)
        }
        computer.add(sw)
        val code = TransferService.pairingCode
        if (on && code != null) {
            val ip = wifiIp()
            computer.add(p("Address: $ip\nPairing code: $code", 20f, 0xFF1A73E8.toInt()))
            computer.add(p("On the computer, run:\npython pixelferry_send.py send \"FOLDER\" --to $ip --code $code --owner YOURNAME --device Laptop", 13f))
            computer.add(p("Same Wi-Fi only. The connection is encrypted and locked to this Pixel; the code changes each time you switch this on.", 13f, 0xFF5F6368.toInt()))
        }
    }

    private fun wifiIp(): String {
        @Suppress("DEPRECATION") val ip = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
        return if (ip == 0) "(connect the Pixel to Wi-Fi)" else "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}.${ip shr 24 and 255}"
    }

    private fun helpCard(): LinearLayout = card().add(
        h("Tips"),
        p("• iPhone full quality: iPhone Settings > Photos > Transfer to Mac or PC > 'Keep Originals' (HEIC stays HEIC, no conversion).\n" +
            "• Leaving iCloud: turn on 'Download and Keep Originals', let it download what fits, transfer, delete, repeat until iCloud is empty.\n" +
            "• Big runs: keep this app open on screen and the Pixel near Wi-Fi. The screen may stay on so the free-up helper can work; you can turn it off, then you get one tap per wave instead.\n" +
            "• The other phone draws power from the Pixel over the cable. PixelFerry pauses at 12% battery and continues at 25%.\n" +
            "• In Google Photos, search a tag like 'Mom_iPhone13' to see everything from that phone.\n" +
            "• Insta360 raw files arrive as NAME_insv.mp4 / NAME_insp.jpg. The bytes are untouched; rename back to .insv/.insp to edit in Insta360 Studio.", 14f)
    )

    companion object {
        const val ACTION_USB_PERMISSION = "app.pixelferry.USB_PERMISSION"
        const val REQ_TREE = 7
    }
}
