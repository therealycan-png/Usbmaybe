package app.pixelferry.sources

import android.graphics.Bitmap
import app.pixelferry.core.CopySource
import app.pixelferry.core.SourceEntry

interface MediaSource : CopySource {
    val deviceKey: String
    /** Suggested name like "iPhone 13" or "CPH2451". */
    val suggestedLabel: String
    val kind: String
    /** False when the device can't be told apart from another one safely. */
    val canDelete: Boolean get() = true
    /** Every photo/video on the device. Can take a while for 10k items. */
    fun list(progress: (Int) -> Unit = {}): List<SourceEntry>
    /** Delete from the device. @return true if the device confirmed. */
    fun delete(entry: SourceEntry): Boolean
    fun thumbnail(entry: SourceEntry): Bitmap?
    fun close()
}
