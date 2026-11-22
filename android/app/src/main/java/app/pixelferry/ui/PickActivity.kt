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
import app.pixelferry.sources.SourceEntry
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
    private val owner by lazy { EditText(this).apply { hint = "Whose files? e.g. Mom, Shawn" } }
    private val label by lazy { EditText(this).apply { hint = "Which device? e.g. iPhone13" } }

    private var mode = 0 // 0 all, 1 dates, 2 folders, 3 grid
    private var from: Long? = null
    private var until: Long? = null
    private val folders = HashSet<String>()
    private val picked = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = src ?: return finish()
        title = "Send from ${s.suggestedLabel}"
        body = vbox().apply { setBackgroundColor(0xFFF1F3F4.toInt()) }
        setContentView(ScrollView(this).apply { addView(body) })
        body.add(p("Reading ${s.suggestedLabel}… (10,000 photos can take a minute or two)"))
        thread {
            val list = try { s.list { n -> runOnUiThread { title = "Reading… $n found" } } } catch (e: Exception) {
                runOnUiThread { body.removeAllViews(); body.add(p("Couldn't read the device: ${e.message}\n\nUnlock it / choose File transfer, then try again.")) }
                return@thread
            }
            val have = Hub.ledger.keysFor(s.deviceKey)
            runOnUiThread { entries = list; done = have; title = "Send from ${s.suggestedLabel}"; build() }
        }
    }

    private fun build() {
        val s = src ?: return
        body.removeAllViews()
        val prof = Hub.ledger.device(s.deviceKey)
        val prefs = getSharedPreferences("pf", 0)
        owner.setText(prof?.owner ?: prefs.getString("lastOwner", "") ?: "")
        label.setText(prof?.label ?: s.suggestedLabel.replace(" ", ""))
        body.add(card().add(h("Who is this from?"), owner, label,
            p("Files are tagged like Mom_iPhone13_IMG_1234.HEIC and land in DCIM/PixelFerry/Mom_iPhone13 so you can tell whose phone they came from.", 13f, 0xFF5F6368.toInt())))

        val left = entries.count { it.itemKey !in done }
        val videos = entries.count { MediaTypes.isVideo(it.name) }
        body.add(card().add(h("On this device"),
            p("${entries.size} photos & videos (${videos} videos), ${fmt(entries.sumOf { it.approxSize })}.\n" +
                "${entries.size - left} already transferred earlier. $left new.")))

        resumeSelection()?.let { keys ->
            val n = entries.count { it.itemKey in keys && it.itemKey !in done }
            if (n > 0) body.add(btn("Resume last transfer ($n left)", primary = true) {
                start(entries.filter { it.itemKey in keys })
            })
        }

        val group = RadioGroup(this)
        listOf("Everything", "By date range", "By folder / album", "Pick photos myself").forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply { text = t; id = 100 + i; textSize = 16f })
        }
        group.check(100 + mode)
        group.setOnCheckedChangeListener { _, id -> mode = id - 100; renderMode() }
        modeBox = vbox(0)
        summary = p(size = 16f, color = 0xFF1A73E8.toInt())
        body.add(card().add(h("What to send"), group, modeBox, summary))
        body.add(btn("Start transfer", primary = true) { start(selection()) })
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
        summary.text = "Selected ${sel.size} (${fmt(sel.sumOf { it.approxSize })}). New to send: ${new.size} (${fmt(new.sumOf { it.approxSize })})."
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
                modeBox.add(btn("From: " + (from?.let { df.format(Date(it)) } ?: "the beginning")) { pick(true) })
                modeBox.add(btn("To: " + (until?.let { df.format(Date(it - 1)) } ?: "today")) { pick(false) })
            }
            2 -> entries.groupBy { it.folder }.toSortedMap().forEach { (f, list) ->
                modeBox.add(CheckBox(this).apply {
                    text = "${f.ifEmpty { "(top level)" }}  ·  ${list.size} items, ${fmt(list.sumOf { it.approxSize })}"
                    isChecked = f in folders; textSize = 15f
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
        if (Hub.run.active) { summary.text = "Another transfer is running."; return }
        val prof = DeviceProfile(s.deviceKey, o, l)
        Hub.ledger.saveDevice(prof)
        getSharedPreferences("pf", 0).edit().putString("lastOwner", o).apply()
        selFile().writeText(sel.joinToString("\n") { it.itemKey })
        TransferService.pendingJob = TransferService.Job(s, prof, sel)
        TransferService.send(this, TransferService.ACTION_RUN)
        finish()
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }
}
