package app.pixelferry.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing code shown on the Pixel, typed on the computer:  K7QM-2XHD-9F3B-WPRA
 *   first 8 chars  = secret token (40 bits, random each time receiving is turned on)
 *   last 8 chars   = first 40 bits of the Pixel's TLS certificate SHA-256
 * The computer refuses to send anything unless the certificate matches, so a
 * stranger on the same Wi-Fi can neither read the photos nor pretend to be the Pixel.
 */
object Pairing {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
    private val rng = SecureRandom()

    fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun newToken(): String { val b = ByteArray(5); rng.nextBytes(b); return base32(b) }

    fun certPin(certDer: ByteArray): String =
        base32(MessageDigest.getInstance("SHA-256").digest(certDer)).substring(0, 8)

    fun code(token: String, pin: String): String {
        val s = token + pin
        return s.chunked(4).joinToString("-")
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
