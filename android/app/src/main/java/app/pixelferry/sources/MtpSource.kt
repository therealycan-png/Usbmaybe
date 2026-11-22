package app.pixelferry.sources

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import app.pixelferry.core.MediaTypes
import app.pixelferry.core.SourceEntry
import app.pixelferry.core.UNKNOWN_SIZE
import java.io.File
import java.io.IOException

/**
 * Reads a phone/camera over the USB cable. The Pixel is the USB host.
 *  - iPhone/iPad: PTP (Apple exposes the camera roll originals: HEIC/MOV as stored)
 *  - Oppo / OnePlus Pad 3 / Android: MTP ("File transfer" mode on that device)
 *  - Insta360 and other cameras: PTP/MTP
 * All MtpDevice calls are serialized: the protocol is one-request-at-a-time.
 */
class MtpSource private constructor(
    private val usb: UsbDevice,
    private val mtp: MtpDevice,
) : MediaSource {

    override val kind = "usb"
    /** Without a serial number two identical phones could look alike, so never delete from them. */
    override val canDelete: Boolean get() = !serial.startsWith("noserial-")

    private val info = mtp.deviceInfo
    private val serial = info?.serialNumber?.takeIf { it.isNotBlank() } ?: usb.serialNumberSafe()?.takeIf { it.isNotBlank() }
        ?: "noserial-${info?.model ?: usb.productName}" // stable across re-plugs (deviceName is not)
    override val deviceKey = "usb:${usb.vendorId}:${usb.productId}:$serial"
    override val suggestedLabel: String = listOfNotNull(info?.model?.takeIf { it.isNotBlank() }, usb.productName)
        .firstOrNull() ?: "USB device"

    @Volatile private var open = true
    override fun isConnected() = open

    private fun UsbDevice.serialNumberSafe(): String? = try { serialNumber } catch (_: SecurityException) { null }

    /** Items/folders that didn't answer during the last listing (device locked mid-way?). */
    @Volatile var lastWarnings = 0; private set

    private fun <T> retry(f: () -> T?): T? = f() ?: run { Thread.sleep(400); f() } ?: run { Thread.sleep(1500); f() }

    override fun list(progress: (Int) -> Unit): List<SourceEntry> = synchronized(mtp) {
        val out = ArrayList<SourceEntry>()
        var warnings = 0
        val storages = retry { mtp.storageIds?.takeIf { it.isNotEmpty() } }
            ?: throw IOException("The device is locked or hasn't allowed access yet. Unlock it and tap Trust / Allow (iPhone) or choose File transfer (Android).")
        for (storage in storages) {
            var rootCount = -1
            // Breadth-first walk. 0xFFFFFFFF = "root of this storage" in PTP/MTP.
            val queue = ArrayDeque<Pair<Int, String>>()
            queue += ROOT to ""
            val seen = HashSet<Int>()
            while (queue.isNotEmpty()) {
                val (parent, path) = queue.removeFirst()
                val handles = retry { mtp.getObjectHandles(storage, 0, parent) }
                if (handles == null) { warnings++; continue }
                if (parent == ROOT) rootCount = handles.size
                for (h in handles) {
                    if (!seen.add(h)) continue
                    val oi = retry { mtp.getObjectInfo(h) }
                    if (oi == null) { warnings++; continue }
                    val name = oi.name ?: continue
                    if (oi.format == MtpConstants.FORMAT_ASSOCIATION) {
                        val sub = if (path.isEmpty()) name else "$path/$name"
                        if (!name.startsWith(".") && sub !in SKIP_DIRS) queue += h to sub
                        continue
                    }
                    if (!MediaTypes.isMedia(name)) continue
                    val size = sizeOf(h, oi)
                    val date = if (oi.dateCreated > 0) oi.dateCreated else oi.dateModified
                    out += SourceEntry(
                        itemKey = "$storage/$path/$name|$size|$date",
                        name = name, size = size, dateMs = date, folder = path, ref = h.toString(),
                    )
                    if (out.size % 100 == 0) progress(out.size)
                }
            }
            if (rootCount == 0) flatList(storage, out) // some devices only answer "all objects" (parent 0)
        }
        lastWarnings = warnings
        progress(out.size)
        out
    }

    private fun flatList(storage: Int, out: MutableList<SourceEntry>) {
        val handles = mtp.getObjectHandles(storage, 0, 0) ?: return
        for (h in handles) {
            val oi = mtp.getObjectInfo(h) ?: continue
            val name = oi.name ?: continue
            if (oi.format == MtpConstants.FORMAT_ASSOCIATION || !MediaTypes.isMedia(name)) continue
            val size = sizeOf(h, oi)
            val date = if (oi.dateCreated > 0) oi.dateCreated else oi.dateModified
            out += SourceEntry("$storage/#${oi.parent}/$name|$size|$date", name, size, date, "Folder ${oi.parent}", h.toString())
        }
    }

    /**
     * PTP stores sizes in 32 bits; 0xFFFFFFFF means "4 GB or more" (long 4K videos).
     * Those are passed on as UNKNOWN_SIZE and verified by their MP4/MOV structure instead.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun sizeOf(handle: Int, oi: MtpObjectInfo): Long {
        val s = oi.compressedSizeLong
        return if (s == 0xFFFFFFFFL) UNKNOWN_SIZE else s
    }

    override fun copyTo(entry: SourceEntry, dest: File) { copyHashed(entry, dest) }

    /** Which partial-read call this device answers (decided on the first file, then reused). */
    private enum class ReadMode { UNKNOWN, P64, P32, IMPORT }
    @Volatile private var mode = ReadMode.UNKNOWN
    private var chunk = CHUNK

    /**
     * Reads the file in chunks with GetPartialObject(64) and hashes it on the way (no second read).
     * Tried in order: 64-bit partial reads (Android phones/tablets), 32-bit (iPhone, cameras),
     * and only then the one-shot importFile, which fails on some newer devices (seen with a
     * OnePlus Pad 3 on a Pixel XL).
     * @return SHA-256 of the bytes written, or null if importFile was used (then it's read back).
     */
    override fun copyHashed(entry: SourceEntry, dest: File): String? = synchronized(mtp) {
        if (!open) throw IOException("The device was unplugged")
        val h = entry.ref.toInt()
        if (mode != ReadMode.IMPORT) {
            val sha = try { readChunked(h, entry.size, dest) } catch (e: IOException) {
                if (!open) throw e
                if (mode == ReadMode.UNKNOWN) null else throw e // a mode that worked before failing now = real error
            }
            if (sha != null) return@synchronized sha
            mode = ReadMode.IMPORT
        }
        val pfd = ParcelFileDescriptor.open(dest,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        pfd.use {
            if (!mtp.importFile(h, it)) throw IOException("The device refused to send ${entry.name}. Keep it unlocked; on Android devices pick \"File transfer\".")
        }
        null
    }

    /** @return SHA-256, or null if this device supports no partial-read call. */
    private fun readChunked(h: Int, size: Long, dest: File): String? {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var buf = ByteArray(chunk)
        dest.outputStream().buffered(1 shl 16).use { o ->
            var off = 0L
            while (size < 0 || off < size) {
                val want = if (size < 0) buf.size.toLong() else minOf(buf.size.toLong(), size - off)
                val n = readOnce(h, off, want, buf)
                if (n < 0) return null // first read: no supported mode
                if (n == 0L) break
                o.write(buf, 0, n.toInt()); md.update(buf, 0, n.toInt()); off += n
                if (size < 0 && n < want) break
                if (buf.size != chunk) buf = ByteArray(chunk)
            }
        }
        return app.pixelferry.core.Hashing.hex(md.digest())
    }

    /** One chunk. Picks the read mode on the very first call. @return bytes read, or -1 if nothing works. */
    private fun readOnce(h: Int, off: Long, want: Long, buf: ByteArray): Long {
        when (mode) {
            ReadMode.P64 -> return mtp.getPartialObject64(h, off, want, buf)
            ReadMode.P32 -> {
                if (off + want > 0xFFFFFFFFL) throw IOException("File over 4 GB: this device can't send it over the cable")
                return mtp.getPartialObject(h, off, want, buf)
            }
            else -> {}
        }
        // first chunk ever: find what the device supports (big chunk, then a small one)
        for (size in intArrayOf(CHUNK, 128 * 1024)) {
            val w = minOf(want, size.toLong())
            try { val n = mtp.getPartialObject64(h, off, w, buf); if (n >= 0) { mode = ReadMode.P64; chunk = size; return n } } catch (_: Exception) {}
            if (!open) throw IOException("The device was unplugged")
            try { val n = mtp.getPartialObject(h, off, w, buf); if (n >= 0) { mode = ReadMode.P32; chunk = size; return n } } catch (_: Exception) {}
            if (!open) throw IOException("The device was unplugged")
        }
        return -1
    }

    override fun delete(entry: SourceEntry): Boolean = synchronized(mtp) {
        // Safety: make sure the handle still points at the same file before deleting.
        val oi = mtp.getObjectInfo(entry.ref.toInt()) ?: return false
        if (oi.name != entry.name || sizeOf(entry.ref.toInt(), oi) != entry.size) return false
        mtp.deleteObject(entry.ref.toInt())
    }

    override fun thumbnail(entry: SourceEntry): Bitmap? = synchronized(mtp) {
        try {
            val b = mtp.getThumbnail(entry.ref.toInt()) ?: return null
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (_: Exception) { null }
    }

    fun isDevice(d: UsbDevice) = d.deviceName == usb.deviceName

    override fun close() { open = false; synchronized(mtp) { mtp.close() } }

    companion object {
        private const val ROOT = -1 // 0xFFFFFFFF
        private const val CHUNK = 1 shl 20
        /** App-private and system folders on Android phones: no camera media, thousands of tiny files. */
        private val SKIP_DIRS = setOf("Android/data", "Android/obb", "Android/obj", ".thumbnails", "DCIM/.thumbnails")

        /** @return null if the device does not speak MTP/PTP (e.g. it is in charge-only mode). */
        fun open(usbManager: UsbManager, device: UsbDevice): MtpSource? {
            val conn = usbManager.openDevice(device) ?: return null
            val mtp = MtpDevice(device)
            if (!mtp.open(conn)) { conn.close(); return null }
            return MtpSource(device, mtp)
        }
    }
}
