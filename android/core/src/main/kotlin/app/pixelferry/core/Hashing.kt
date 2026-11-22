package app.pixelferry.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUF = 1 shl 20

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUF)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    /** Copy exactly [size] bytes (or to EOF if size < 0) and return the SHA-256 of what was written. */
    fun copyHashing(input: InputStream, output: java.io.OutputStream, size: Long = -1): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUF)
        var left = size
        while (size < 0 || left > 0) {
            val n = input.read(buf, 0, if (size < 0) buf.size else minOf(buf.size.toLong(), left).toInt())
            if (n < 0) { if (size < 0) break else throw java.io.EOFException("sender disconnected") }
            output.write(buf, 0, n); md.update(buf, 0, n); left -= n
        }
        return hex(md.digest())
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}
