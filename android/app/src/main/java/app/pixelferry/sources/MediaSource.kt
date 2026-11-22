package app.pixelferry.sources

import android.graphics.Bitmap
import java.io.File

/** One photo/video on a source device. */
data class SourceEntry(
    /** Stable across reconnects: storage + path + size + date. */
    val itemKey: String,
    val name: String,
    val size: Long,
    val dateMs: Long,
    /** Folder path on the device, e.g. "DCIM/100APPLE". */
    val folder: String,
    /** Opaque handle (MTP object handle or document URI). */
    val ref: String,
) {
    /** For progress and space math: size, or a 4 GB+ estimate when the device can't say. */
    val approxSize: Long get() = if (size >= 0) size else app.pixelferry.core.BIG_FILE_ESTIMATE
}

interface MediaSource {
    val deviceKey: String
    /** Suggested name like "iPhone 13" or "CPH2451". */
    val suggestedLabel: String
    val kind: String
    fun isConnected(): Boolean
    /** Every photo/video on the device. Can take a while for 10k items. */
    fun list(progress: (Int) -> Unit = {}): List<SourceEntry>
    /** Write the exact original bytes into [dest]. */
    fun copyTo(entry: SourceEntry, dest: File)
    /** Delete from the device. @return true if the device confirmed. */
    fun delete(entry: SourceEntry): Boolean
    fun thumbnail(entry: SourceEntry): Bitmap?
    fun close()
}
