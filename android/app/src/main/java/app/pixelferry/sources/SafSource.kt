package app.pixelferry.sources

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.pixelferry.core.MediaTypes
import app.pixelferry.core.SourceEntry
import java.io.File
import java.io.IOException

/**
 * Any folder Android can open: SD card in a reader, USB drive, a camera in
 * mass-storage mode, or a folder on the Pixel itself. Uses the system folder picker,
 * so the app only ever sees the folder you chose.
 */
class SafSource(private val cr: ContentResolver, private val tree: Uri, label: String) : MediaSource {
    override val kind = "folder"
    override val deviceKey = "saf:$tree"
    override val suggestedLabel = label
    override fun isConnected() = try {
        cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
    } catch (_: Exception) { false }

    override fun list(progress: (Int) -> Unit): List<SourceEntry> {
        val out = ArrayList<SourceEntry>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue += DocumentsContract.getTreeDocumentId(tree) to ""
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        while (queue.isNotEmpty()) {
            val (docId, path) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) queue += id to (if (path.isEmpty()) name else "$path/$name")
                        continue
                    }
                    if (!MediaTypes.isMedia(name)) continue
                    val size = c.getLong(3); val date = c.getLong(4)
                    out += SourceEntry("$path/$name|$size|$date", name, size, date, path,
                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
                    if (out.size % 200 == 0) progress(out.size)
                }
            }
        }
        progress(out.size)
        return out
    }

    override fun copyTo(entry: SourceEntry, dest: File) {
        val input = cr.openInputStream(Uri.parse(entry.ref)) ?: throw IOException("Cannot open ${entry.name}")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
    }

    override fun delete(entry: SourceEntry): Boolean = try {
        DocumentsContract.deleteDocument(cr, Uri.parse(entry.ref))
    } catch (_: Exception) { false }

    override fun thumbnail(entry: SourceEntry): Bitmap? = try {
        DocumentsContract.getDocumentThumbnail(cr, Uri.parse(entry.ref), Point(240, 240), null)
    } catch (_: Exception) { null }

    override fun close() {}
}
