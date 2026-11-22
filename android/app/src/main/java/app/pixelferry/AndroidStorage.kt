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

    private var index: Set<String> = emptySet()

    /** Fresh list of name+size of every media file OUTSIDE DCIM/PixelFerry. */
    @Synchronized fun refreshElsewhere() {
        val ext = Environment.getExternalStorageDirectory()
        val mine = rootDir().canonicalFile
        val roots = listOf("DCIM", "Pictures", "Download", "Movies").map { File(ext, it) }
        index = roots.flatMap { r ->
            r.walkTopDown().onEnter { it.canonicalFile != mine }.maxDepth(6).filter { it.isFile }.map { "${it.name}|${it.length()}" }.toList()
        }.toHashSet()
    }

    /** Was a vanished file merely moved (e.g. by a cleaner app) rather than freed by Google? */
    @Synchronized fun existsElsewhere(name: String, size: Long): Boolean = "$name|$size" in index

    override fun syncDir(dir: File) {
        try {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        } catch (_: Exception) {}
    }

    override fun publish(file: File, mime: String, dateTakenMs: Long) {
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
    }
}
