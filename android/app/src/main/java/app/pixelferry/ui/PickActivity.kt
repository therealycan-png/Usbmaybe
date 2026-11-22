package app.pixelferry.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import app.pixelferry.Hub
import app.pixelferry.TransferService
import app.pixelferry.TransferService.Companion.fmt
import app.pixelferry.core.DeviceProfile
import app.pixelferry.core.MediaTypes
import app.pixelferry.core.SourceEntry
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Choose what to send: all, by date, by folder, or tick thumbnails. */
class PickActivity : Activity() {
    private val src get() = Hub.source
    private var entries: List<SourceEntry> = emptyList()
    private var done: Set<String> = emptySet()
    private lateinit var body: LinearLayout
    private lateinit var modeBox: LinearLayout
    private lateinit var summary: TextView
    private val owner by lazy { EditText(this).apply { hint = "e.g. Mom" } }
    private val label by lazy { EditText(this).apply { hint = "e.g. iPhone13" } }

    private var mode = 0 // 0 all, 1 dates, 2 folders, 3 grid
    private var tooBigAccepted = false
    private var warningsAccepted = false
    private var listWarnings = 0
    private var from: Long? = null
    private var until: Long? = null
    private val folders = HashSet<String>()
    private val picked = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = src ?: return finish()
        actionBar?.hide()
        window.statusBarColor = C.PRIMARY
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        body = vbox(0).apply { setBackgroundColor(C.BG); setPadding(0, 0, 0, dp(16)) }
        sendBar = vbox(0).apply {
            setBackgroundColor(C.CARD); elevation = dpf(12f); setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        setContentView(vbox(0).apply {
            setPadding(0, 0, 0, 0); setBackgroundColor(C.BG)
            addView(ScrollView(context).apply { setBackgroundColor(C.BG); addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(sendBar, LinearLayout.LayoutParams(-1, -2))
        })
        val loading = p("Looking through ${s.suggestedLabel}…", 17f)
        body.add(hero(s.suggestedLabel, "Reading photos and videos", "📱"),
            card().add(centered(ProgressRing(this).apply { indeterminate = true; center = "" }), loading))
        thread {
            val list = try { s.list { n -> runOnUiThread { loading.text = "Found %,d photos & videos so far…".format(n) } } } catch (e: Exception) {
                runOnUiThread {
                    body.removeAllViews()
                    body.add(hero(s.suggestedLabel, "Can't read it yet", "📱"),
                        card(C.TINT_AMBER).add(h("Almost there"), p("${e.message}\n\nThen go back and tap the device again.")))
                }
                return@thread
            }
            val have = Hub.ledger.forDevice(s.deviceKey).filter { Hub.ledger.isDone(it) }.map { it.itemKey }.toSet()
            listWarnings = (s as? app.pixelferry.sources.MtpSource)?.lastWarnings ?: 0
            runOnUiThread { if (!isDestroyed) { entries = list; done = have; build() } }
        }
    }

    private fun build() {
        val s = src ?: return
        body.removeAllViews()
        val prof = Hub.ledger.device(s.deviceKey)
        val prefs = getSharedPreferences("pf", 0)
        owner.setText(prof?.owner ?: prefs.getString("lastOwner", "") ?: "")
        label.setText(prof?.label ?: s.suggestedLabel.replace(" ", ""))
        styleField(owner); styleField(label)

        val left = entries.count { it.itemKey !in done }
        val videos = entries.count { MediaTypes.isVideo(it.name) }
        body.add(hero(prof?.let { "${it.owner}'s ${it.label}" } ?: s.suggestedLabel,
            "%,d photos · %,d videos · %s".format(entries.size - videos, videos, fmt(entries.sumOf { it.approxSize })), "📱"))
        if (entries.size - left > 0) body.add(card(C.TINT_GREEN).add(
            chip("✓  %,d already sent before".format(entries.size - left), C.SUCCESS, C.CARD),
            p("They're skipped automatically. %,d new to send.".format(left), 15f)))

        resumeSelection()?.let { keys ->
            val n = entries.count { it.itemKey in keys && it.itemKey !in done }
            if (n > 0) body.add(card(C.TINT_BLUE).add(h("Pick up where you left off"),
                p("%,d files from last time are still to go.".format(n)),
                btn("Resume (%,d left)".format(n), primary = true) { start(entries.filter { it.itemKey in keys }) }))
        }

        // 1. whose
        val owners = (Hub.ledger as? app.pixelferry.SqliteLedger)?.devices()?.map { it.owner }?.distinct()?.take(5) ?: emptyList()
        val whoCard = card().add(h("1. Whose photos?"),
            p("They'll be tagged like Mom_iPhone13_IMG_1234 so you can find them in Google Photos.", 15f))
        if (owners.isNotEmpty()) whoCard.addView(hbox().apply {
            setPadding(0, dp(6), 0, dp(2))
            owners.forEach { o -> addView(chip(o, C.PRIMARY, C.TINT_BLUE).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
                minHeight = dp(40); gravity = android.view.Gravity.CENTER
                setOnClickListener { owner.setText(o) }
            }) }
        })
        whoCard.add(text("Name", 13f, C.INK2).apply { setPadding(0, dp(10), 0, dp(4)) }, owner,
            text("Device", 13f, C.INK2).apply { setPadding(0, dp(10), 0, dp(4)) }, label)
        body.add(whoCard)

        // 2. what
        modeBox = vbox(0).apply { setPadding(0, dp(6), 0, 0) }
        summary = text("", 15f, C.INK2)
        val modes = listOf(Triple("🖼️", "Everything", "All photos & videos"), Triple("📅", "By date", "Pick a range"),
            Triple("🗂️", "By folder", "Camera, WhatsApp…"), Triple("✋", "Choose myself", "Pick the ones you want"))
        val grid = vbox(0)
        modeCards.clear()
        modes.chunked(2).forEachIndexed { r, row ->
            grid.addView(hbox().apply {
                gravity = android.view.Gravity.TOP
                row.forEachIndexed { c, (sym, t, sub) ->
                    val i = r * 2 + c
                    val cell = vbox(14).apply {
                        isClickable = true; contentDescription = "$t. $sub"
                        addView(emoji(sym, 26f)); addView(text(t, 16f, C.INK, bold = true)); addView(text(sub, 13.5f, C.INK2))
                        setPadding(dp(16), dp(16), dp(16), dp(18))
                        setOnClickListener { mode = i; paintModes(); renderMode() }
                    }
                    modeCards += cell
                    addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(if (c == 0) 0 else dp(6), 0, if (c == 0) dp(6) else 0, dp(12)) })
                }
            })
        }
        body.add(card().add(h("2. What to send?"), grid, modeBox))
        paintModes()

        sendBar.removeAllViews()
        sendBar.add(summary, btn("Send", primary = true) { start(selection()) }.also { sendBtn = it })
        renderMode()
    }

    private fun selection(): List<SourceEntry> = when (mode) {
        1 -> entries.filter { e -> (from == null || e.dateMs >= from!!) && (until == null || e.dateMs < until!!) }
        2 -> entries.filter { it.folder in folders }
        3 -> entries.filter { it.itemKey in picked }
        else -> entries
    }

    private fun updateSummary() {
        val sel = selection()
        val new = sel.filter { it.itemKey !in done }
        summary.text = if (sel.size == new.size) "%,d selected".format(sel.size) else "%,d selected · %,d already sent".format(sel.size, sel.size - new.size)
        sendBtn?.text = if (new.isEmpty()) "Nothing new to send" else "Send %,d · %s  →".format(new.size, fmt(new.sumOf { it.approxSize }))
        sendBtn?.isEnabled = new.isNotEmpty()
        sendBtn?.alpha = if (new.isEmpty()) 0.5f else 1f
    }

    private val modeCards = ArrayList<LinearLayout>()
    private var sendBtn: android.widget.Button? = null
    private lateinit var sendBar: LinearLayout

    private fun paintModes() = modeCards.forEachIndexed { i, v ->
        v.background = ripple(rounded(if (i == mode) C.TINT_BLUE else C.CARD, dpf(18f), dp(2), if (i == mode) C.PRIMARY else C.LINE),
            rounded(android.graphics.Color.BLACK, dpf(18f)))
    }

    private fun needSetup(why: String) {
        android.app.AlertDialog.Builder(this).setTitle("One quick step first").setMessage(why)
            .setPositiveButton("Do it now") { _, _ -> startActivity(android.content.Intent(this, SetupActivity::class.java)) }
            .setNegativeButton("Later", null).show()
    }

    private fun styleField(e: EditText) {
        e.textSize = 18f; e.setTextColor(C.INK); e.setHintTextColor(C.INK2); e.setSingleLine()
        e.background = rounded(C.BG, dpf(14f), dp(1), C.LINE); e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.minHeight = dp(52)
    }

    private val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private fun renderMode() {
        modeBox.removeAllViews()
        when (mode) {
            1 -> {
                fun pick(isFrom: Boolean) {
                    val c = Calendar.getInstance()
                    DatePickerDialog(this, { _, y, m, d ->
                        c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
                        if (isFrom) from = c.timeInMillis else { c.add(Calendar.DAY_OF_MONTH, 1); until = c.timeInMillis }
                        renderMode()
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
                modeBox.add(btn("From  " + (from?.let { df.format(Date(it)) } ?: "the very first photo")) { pick(true) })
                modeBox.add(btn("To  " + (until?.let { df.format(Date(it - 1)) } ?: "today")) { pick(false) })
            }
            2 -> entries.groupBy { it.folder }.toSortedMap().forEach { (f, list) ->
                modeBox.add(CheckBox(this).apply {
                    text = "${f.ifEmpty { "(top level)" }}\n%,d items · %s".format(list.size, fmt(list.sumOf { it.approxSize }))
                    isChecked = f in folders; textSize = 16f; setTextColor(C.INK); minHeight = dp(56)
                    buttonTintList = android.content.res.ColorStateList.valueOf(C.PRIMARY)
                    setOnCheckedChangeListener { _, c -> if (c) folders += f else folders -= f; updateSummary() }
                })
            }
            3 -> modeBox.add(grid())
        }
        updateSummary()
    }

    // ---------- thumbnail grid ----------
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loader = Executors.newSingleThreadExecutor()

    private fun grid(): View {
        val sorted = entries.sortedByDescending { it.dateMs }
        val g = GridView(this).apply {
            numColumns = 4; verticalSpacing = dp(2); horizontalSpacing = dp(2)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(480))
            // let the grid scroll inside the page
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = sorted.size
            override fun getItem(i: Int) = sorted[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val e = sorted[i]
                val cell = (convert as? FrameLayout) ?: FrameLayout(this@PickActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88))
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFFDADCE0.toInt()) },
                        FrameLayout.LayoutParams(-1, -1))
                    addView(TextView(context).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(4), 0, 0, 0) })
                }
                val img = cell.getChildAt(0) as ImageView
                val mark = cell.getChildAt(1) as TextView
                img.tag = e.itemKey
                val cached = thumbs.get(e.itemKey)
                img.setImageBitmap(cached)
                if (cached == null) loader.execute {
                    if (img.tag != e.itemKey) return@execute
                    val b = src?.thumbnail(e) ?: return@execute
                    thumbs.put(e.itemKey, b)
                    runOnUiThread { if (img.tag == e.itemKey) img.setImageBitmap(b) }
                }
                mark.text = when { e.itemKey in picked -> "✔"; e.itemKey in done -> "☁"; MediaTypes.isVideo(e.name) -> "▶"; else -> "" }
                cell.alpha = if (e.itemKey in picked) 0.6f else 1f
                return cell
            }
        }
        g.adapter = adapter
        g.setOnItemClickListener { _, _, i, _ ->
            val k = sorted[i].itemKey
            if (!picked.add(k)) picked.remove(k)
            adapter.notifyDataSetChanged(); updateSummary()
        }
        return vbox(0).add(
            p("Tap to select. ✔ selected · ☁ already transferred · ▶ video", 13f),
            btn("Select all new") { sorted.filter { it.itemKey !in done }.forEach { picked += it.itemKey }; adapter.notifyDataSetChanged(); updateSummary() },
            g)
    }

    // ---------- start ----------
    private fun selFile(): File {
        val h = MessageDigest.getInstance("SHA-256").digest(src!!.deviceKey.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(filesDir, "selection-$h.txt")
    }

    private fun resumeSelection(): Set<String>? = selFile().takeIf { it.exists() }?.readLines()?.toSet()

    private fun start(sel: List<SourceEntry>) {
        val s = src ?: return
        val o = owner.text.toString().trim(); val l = label.text.toString().trim()
        if (o.isEmpty() || l.isEmpty()) { owner.error = if (o.isEmpty()) "Needed for the divider" else null; label.error = if (l.isEmpty()) "Needed" else null; return }
        if (sel.isEmpty()) { summary.text = "Nothing selected."; return }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needSetup("PixelFerry needs permission to save photos on this Pixel first."); return
        }
        if (!getSharedPreferences("pf", 0).getBoolean("qualityOriginal", false)) {
            needSetup("First check that Google Photos backs up in Original quality, so nothing gets compressed."); return
        }
        val tooBig = sel.filter { it.size > GOOGLE_MAX_VIDEO }
        val sendable = sel - tooBig.toSet()
        if (tooBig.isNotEmpty() && !tooBigAccepted) {
            android.app.AlertDialog.Builder(this).setTitle("${tooBig.size} videos are too big for Google Photos")
                .setMessage("Google Photos can't back up videos over 10 GB, so these stay on the device and are never offered for deletion:\n\n" +
                    tooBig.take(8).joinToString("\n") { "• ${it.name} (${fmt(it.size)})" } + "\n\nKeep them on a computer or drive.")
                .setPositiveButton("Send the rest") { _, _ -> tooBigAccepted = true; start(sel) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (listWarnings > 0 && !warningsAccepted) {
            android.app.AlertDialog.Builder(this).setTitle("Some folders couldn't be read")
                .setMessage("$listWarnings items or folders didn't answer (the device may have locked). Keep it unlocked and go back and open it again to see everything, or send what was found now.")
                .setPositiveButton("Send what was found") { _, _ -> warningsAccepted = true; start(sel) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (Hub.run.active) { summary.text = "Another transfer is running."; return }
        val prof = DeviceProfile(s.deviceKey, o, l)
        Hub.ledger.saveDevice(prof)
        getSharedPreferences("pf", 0).edit().putString("lastOwner", o).apply()
        selFile().writeText(sendable.joinToString("\n") { it.itemKey })
        TransferService.pendingJob = TransferService.Job(s, prof, sendable)
        TransferService.send(this, TransferService.ACTION_RUN)
        finish()
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    companion object { const val GOOGLE_MAX_VIDEO = 10L * 1024 * 1024 * 1024 }
}
