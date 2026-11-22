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

    /** Storage mounted, permission granted, our folder listable. */
    fun isReadable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            rootDir().list() != null

    private var nameIndex: Set<String> = emptySet()
    private var indexedAt = 0L

    /** Was a vanished file merely moved (e.g. by a cleaner app) rather than freed by Google? */
    @Synchronized fun existsElsewhere(name: String): Boolean {
        if (System.currentTimeMillis() - indexedAt > 30_000) {
            val ext = Environment.getExternalStorageDirectory()
            val roots = listOf("DCIM", "Pictures", "Download", "Movies").map { File(ext, it) }
            nameIndex = roots.flatMap { r -> r.walkTopDown().maxDepth(6).filter { it.isFile }.map { it.name }.toList() }.toHashSet()
            indexedAt = System.currentTimeMillis()
        }
        return name in nameIndex
    }

    override fun publish(file: File, mime: String, dateTakenMs: Long) {
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
    }
}
