package app.pixelferry

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import app.pixelferry.core.Naming
import app.pixelferry.core.PixelStorage
import java.io.File

/**
 * Files go to /sdcard/DCIM/PixelFerry/<Owner_Device>/ (Android 10 legacy storage),
 * then get media-scanned so Google Photos sees them as a device folder to back up.
 */
class AndroidStorage(private val ctx: Context) : PixelStorage {
    override fun rootDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), Naming.ROOT_FOLDER).also { it.mkdirs() }

    override fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    override fun publish(file: File, mime: String, dateTakenMs: Long) {
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
    }
}
