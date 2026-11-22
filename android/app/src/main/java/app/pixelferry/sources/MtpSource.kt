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

    private val info = mtp.deviceInfo
    private val serial = info?.serialNumber?.takeIf { it.isNotBlank() } ?: usb.serialNumberSafe() ?: usb.deviceName
    override val deviceKey = "usb:${usb.vendorId}:${usb.productId}:$serial"
    override val suggestedLabel: String = listOfNotNull(info?.model?.takeIf { it.isNotBlank() }, usb.productName)
        .firstOrNull() ?: "USB device"

    @Volatile private var open = true
    override fun isConnected() = open

    private fun UsbDevice.serialNumberSafe(): String? = try { serialNumber } catch (_: SecurityException) { null }

    override fun list(progress: (Int) -> Unit): List<SourceEntry> = synchronized(mtp) {
        val out = ArrayList<SourceEntry>()
        val storages = mtp.storageIds ?: throw IOException("Device is locked or not in file-transfer mode")
        for (storage in storages) {
            val before = out.size
            // Breadth-first walk. 0xFFFFFFFF = "root of this storage" in PTP/MTP.
            val queue = ArrayDeque<Pair<Int, String>>()
            queue += ROOT to ""
            val seen = HashSet<Int>()
            while (queue.isNotEmpty()) {
                val (parent, path) = queue.removeFirst()
                val handles = mtp.getObjectHandles(storage, 0, parent) ?: continue
                for (h in handles) {
                    if (!seen.add(h)) continue
                    val oi = mtp.getObjectInfo(h) ?: continue
                    val name = oi.name ?: continue
                    if (oi.format == MtpConstants.FORMAT_ASSOCIATION) {
                        if (!name.startsWith(".")) queue += h to (if (path.isEmpty()) name else "$path/$name")
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
            if (out.size == before) flatList(storage, out) // some devices only answer "all objects" (parent 0)
        }
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

    override fun copyTo(entry: SourceEntry, dest: File) = synchronized(mtp) {
        val pfd = ParcelFileDescriptor.open(dest,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        pfd.use {
            if (!mtp.importFile(entry.ref.toInt(), it)) throw IOException("Device stopped sending ${entry.name} (unplugged or locked?)")
        }
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

        /** @return null if the device does not speak MTP/PTP (e.g. it is in charge-only mode). */
        fun open(usbManager: UsbManager, device: UsbDevice): MtpSource? {
            val conn = usbManager.openDevice(device) ?: return null
            val mtp = MtpDevice(device)
            if (!mtp.open(conn)) { conn.close(); return null }
            return MtpSource(device, mtp)
        }
    }
}
