package app.pixelferry.core

/**
 * Which files we move, and how we name them so Google Photos backs them up
 * without the bytes ever changing.
 */
object MediaTypes {
    private val photo = setOf(
        "jpg", "jpeg", "heic", "heif", "png", "gif", "webp", "bmp", "avif",
        "dng", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw", "pef"
    )
    private val video = setOf(
        "mp4", "mov", "m4v", "3gp", "3g2", "mkv", "avi", "webm", "mts", "m2ts", "ts", "wmv", "mpg", "mpeg"
    )

    /** Insta360 raw formats: really MP4 / JPEG containers. Renamed, not converted. */
    private val wrapped = mapOf(
        "insv" to "mp4",
        "insp" to "jpg",
    )

    /** Junk/proxy files that are not the user's real media. */
    private val skipped = setOf(
        "lrv", // Insta360 / GoPro low-res preview proxy
        "thm", // thumbnail sidecar
        "aae", // iPhone edit sidecar (Google Photos ignores it)
    )

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isMedia(name: String): Boolean {
        if (name.startsWith(".")) return false
        val e = ext(name)
        return e in photo || e in video || e in wrapped
    }

    fun isSkippedSidecar(name: String): Boolean = ext(name) in skipped

    fun isVideo(name: String): Boolean {
        val e = ext(name)
        return e in video || e == "insv"
    }

    /**
     * File name on the Pixel. Bytes identical; only the name changes.
     *   IMG_1234.HEIC        -> IMG_1234.HEIC
     *   VID_2024_01_01.insv  -> VID_2024_01_01_insv.mp4   (rename back to restore)
     */
    fun pixelSafeName(original: String): String {
        val e = ext(original)
        val mapped = wrapped[e] ?: return original
        val base = original.substring(0, original.length - e.length - 1)
        return "${base}_$e.$mapped"
    }

    fun mimeFor(pixelName: String): String = when (ext(pixelName)) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "dng" -> "image/x-adobe-dng"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        else -> if (isVideo(pixelName)) "video/*" else "image/*"
    }
}
