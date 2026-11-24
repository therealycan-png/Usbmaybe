package app.pixelferry

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hands the computer sender to another app (Nearby Share, Drive, Bluetooth, email) as one zip,
 * read-only, only for the share the user started. Nothing else on the Pixel is reachable.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "share").canonicalFile
        val f = File(dir, uri.lastPathSegment ?: throw FileNotFoundException()).canonicalFile
        if (f.parentFile != dir || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        /** Build PixelFerry-Send.zip (sender + this Pixel's pairing code) and open the share sheet. */
        /** Share any file already placed in cacheDir/share (e.g. the diagnostics zip). */
        fun shareFile(ctx: Context, file: File, subject: String) {
            val uri = Uri.parse("content://app.pixelferry.share/${file.name}")
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun shareSender(ctx: Context, code: String) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val zip = File(dir, "PixelFerry-Send.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                for (name in ctx.assets.list("sender") ?: emptyArray()) {
                    z.putNextEntry(ZipEntry("PixelFerry Send/$name")); ctx.assets.open("sender/$name").use { it.copyTo(z) }; z.closeEntry()
                }
                z.putNextEntry(ZipEntry("PixelFerry Send/pixelferry-pairing.txt"))
                z.write("$code\n".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("PixelFerry Send/READ ME FIRST.txt"))
                z.write(("1. Extract this zip (right-click > Extract All).\r\n" +
                    "2. Windows: double-click \"PixelFerry Send\". Linux: run ./pixelferry-send.sh\r\n" +
                    "3. Choose your photo folder. It finds the Pixel by itself (same Wi-Fi) - no code to type.\r\n" +
                    "Python is fetched automatically on Windows the first time if it isn't installed.\r\n" +
                    "If Windows says \"Windows protected your PC\": click More info, then Run anyway (it's the PixelFerry sender you just sent yourself).\r\n" +
                    "Later, double-click it again: it offers to delete the photos Google Photos has already backed up.\r\n").toByteArray())
                z.closeEntry()
            }
            val uri = Uri.parse("content://app.pixelferry.share/${zip.name}")
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "PixelFerry Send for my computer")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Send PixelFerry to your computer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
