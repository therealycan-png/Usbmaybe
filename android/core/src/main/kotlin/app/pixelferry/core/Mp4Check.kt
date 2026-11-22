package app.pixelferry.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Structural completeness check for MP4/MOV/INSV files when the source can't tell us
 * the exact size (PTP's 32-bit size field: videos over 4 GB).
 * Top-level boxes must chain exactly to the end of the file, and a 'moov' box must exist.
 * A file cut off mid-transfer fails this check.
 */
object Mp4Check {
    fun isComplete(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            var pos = 0L; var moov = false; var boxes = 0
            val hdr = ByteArray(16)
            while (pos < len) {
                if (len - pos < 8) return false
                raf.seek(pos); raf.readFully(hdr, 0, 8)
                var size = ((hdr[0].toLong() and 0xff) shl 24) or ((hdr[1].toLong() and 0xff) shl 16) or
                    ((hdr[2].toLong() and 0xff) shl 8) or (hdr[3].toLong() and 0xff)
                val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                if (size == 1L) {
                    if (len - pos < 16) return false
                    raf.readFully(hdr, 8, 8)
                    size = 0
                    for (i in 8 until 16) size = (size shl 8) or (hdr[i].toLong() and 0xff)
                } else if (size == 0L) return false // "runs to end of file" can't prove completeness
                if (size < 8 || pos + size > len) return false
                if (type == "moov") moov = true
                pos += size; boxes++
            }
            moov && boxes >= 2 && pos == len
        }
    } catch (_: Exception) { false }
}
