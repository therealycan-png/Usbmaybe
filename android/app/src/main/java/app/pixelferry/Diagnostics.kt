package app.pixelferry

import android.content.Context
import android.os.Build
import android.os.Environment
import app.pixelferry.core.FailReason
import app.pixelferry.core.Failure
import app.pixelferry.core.ItemState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything needed to find out why something went wrong, written the moment it happens:
 *  - Download/PixelFerry/diagnostics.log   (events, errors, crashes; easy to pull from a connected computer)
 *  - Download/PixelFerry/failed-<device>.tsv (files not sent yet, with the reason)
 *  - Download/PixelFerry/status.txt        (snapshot: app version, phone, storage, counts)
 * A copy of each also lives inside the app (filesDir/diagnostics) in case storage permission is missing.
 * Nothing leaves the phone unless you share it.
 */
object Diagnostics {
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun dirs(ctx: Context): List<File> = listOfNotNull(
        File(ctx.filesDir, "diagnostics"),
        try { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PixelFerry") } catch (_: Exception) { null },
    ).onEach { it.mkdirs() }

    /** Folder a computer (or Claude on it) should read: adb pull /sdcard/Download/PixelFerry */
    fun publicDir(ctx: Context): File = dirs(ctx).last()

    @Synchronized fun log(ctx: Context, event: String, detail: String = "") {
        val line = "${ts.format(Date())} | $event | ${detail.replace('\n', ' ')}\n"
        for (d in dirs(ctx)) try {
            val f = File(d, "diagnostics.log")
            if (f.length() > 4L * 1024 * 1024) f.renameTo(File(d, "diagnostics.old.log")) // keep it small
            f.appendText(line)
        } catch (_: Exception) {}
    }

    fun crash(ctx: Context, t: Throwable) {
        log(ctx, "CRASH", android.util.Log.getStackTraceString(t).take(6000))
        snapshot(ctx)
    }

    private fun failedFile(d: File, deviceKey: String) =
        File(d, "failed-" + deviceKey.replace(Regex("[^A-Za-z0-9]+"), "_").take(60) + ".tsv")

    data class Failed(val itemKey: String, val name: String, val size: Long, val reason: FailReason, val detail: String, val at: String)

    fun failed(ctx: Context, deviceKey: String): List<Failed> {
        val f = failedFile(dirs(ctx).first(), deviceKey)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { l ->
            val c = l.split('\t'); if (c.size < 6) null
            else Failed(c[0], c[1], c[2].toLongOrNull() ?: 0, runCatching { FailReason.valueOf(c[3]) }.getOrDefault(FailReason.OTHER), c[4], c[5])
        }
    }

    /** Merge this run's failures into the device's list; drop anything that has since been sent. */
    @Synchronized fun recordFailures(ctx: Context, deviceKey: String, label: String, run: List<Failure>) {
        val keep = LinkedHashMap<String, Failed>()
        failed(ctx, deviceKey).forEach { keep[it.itemKey] = it }
        val now = ts.format(Date())
        run.forEach { keep[it.entry.itemKey] = Failed(it.entry.itemKey, it.entry.name, it.entry.size, it.reason, it.detail.take(300), now) }
        val done = Hub.ledger.forDevice(deviceKey).filter { Hub.ledger.isDone(it) }.map { it.itemKey }.toSet()
        val list = keep.values.filter { it.itemKey !in done }
        val text = "itemKey\tname\tsize\treason\tdetail\twhen\n" + list.joinToString("") {
            "${it.itemKey}\t${it.name}\t${it.size}\t${it.reason.name}\t${it.detail.replace('\t', ' ').replace('\n', ' ')}\t${it.at}\n"
        }
        for (d in dirs(ctx)) try { failedFile(d, deviceKey).writeText(text) } catch (_: Exception) {}
        if (run.isNotEmpty()) log(ctx, "FAILED", "$label: ${run.size} this run; ${list.size} still not sent. " +
            run.groupBy { it.reason }.entries.joinToString("; ") { "${it.key}=${it.value.size} e.g. ${it.value.first().entry.name}: ${it.value.first().detail.take(160)}" })
    }

    /** One-file summary of the phone and the app's state. */
    fun snapshot(ctx: Context) {
        val text = try {
            val l = Hub.ledger
            buildString {
                appendLine("PixelFerry status ${ts.format(Date())}")
                appendLine("app ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}  phone ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
                appendLine("free on Pixel: ${Hub.storage.freeBytes()} bytes; reserve ${Hub.engine.reserveBytes}")
                appendLine("ledger: " + ItemState.values().joinToString(", ") { "$it=${l.countState(it)}" })
                appendLine("run: phase=${Hub.run.phase} title=${Hub.run.title} done=${Hub.run.done}/${Hub.run.total} failed=${Hub.run.failed}")
                appendLine("devices:")
                l.devices().forEach { appendLine("  ${it.owner}'s ${it.label}  key=${it.deviceKey}  not-sent=${failed(ctx, it.deviceKey).size}") }
                appendLine("recent problems: " + Hub.run.errors.takeLast(10).joinToString(" || "))
            }
        } catch (e: Exception) { "snapshot failed: $e" }
        for (d in dirs(ctx)) try { File(d, "status.txt").writeText(text) } catch (_: Exception) {}
    }

    /** Zip of everything, for the share button. */
    fun zip(ctx: Context): File {
        snapshot(ctx)
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "PixelFerry-diagnostics.zip")
        java.util.zip.ZipOutputStream(out.outputStream()).use { z ->
            dirs(ctx).first().listFiles()?.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }
}

/** Phone model codes -> names people use (folder and tag names). */
object DeviceNames {
    private val known = mapOf(
        "OPD2415" to "OnePlus Pad 3",
        "OPD2403" to "OnePlus Pad 2",
        "OPD2203" to "OnePlus Pad",
        "OPD2304" to "OnePlus Pad Go",
    )
    fun friendly(model: String?): String? = model?.trim()?.let { m -> known[m.uppercase()] ?: known.entries.firstOrNull { m.uppercase().startsWith(it.key) }?.value }
    fun isRawCode(label: String) = known.containsKey(label.trim().uppercase())
}
