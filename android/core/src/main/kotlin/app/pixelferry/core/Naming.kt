package app.pixelferry.core

import java.io.File

/**
 * The "divider": every file carries owner + device in its folder AND its name,
 * so it stays searchable in Google Photos after the Pixel copy is freed.
 *
 *   DCIM/PixelFerry/Mom_iPhone13/Mom_iPhone13_IMG_1234.HEIC
 *
 * One folder per device (not per month) on purpose: Google Photos asks you to
 * turn on backup once per new device folder; per-month folders would ask every month.
 */
object Naming {
    const val ROOT_FOLDER = "PixelFerry"

    fun sanitizeTag(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^A-Za-z0-9-]"), "")
        return cleaned.take(24).ifEmpty { "Unknown" }
    }

    fun tag(owner: String, device: String): String = "${sanitizeTag(owner)}_${sanitizeTag(device)}"

    fun folderName(owner: String, device: String): String = tag(owner, device)

    fun sanitizeFileName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return n.ifEmpty { "file" }.take(150)
    }

    fun targetName(owner: String, device: String, originalName: String): String {
        val safe = sanitizeFileName(MediaTypes.pixelSafeName(originalName.substringAfterLast('/')))
        val prefix = tag(owner, device) + "_"
        return if (safe.startsWith(prefix)) safe else prefix + safe
    }

    /** Never overwrite: IMG.jpg, IMG~2.jpg, IMG~3.jpg ... */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            f = File(dir, "$base~$i$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
