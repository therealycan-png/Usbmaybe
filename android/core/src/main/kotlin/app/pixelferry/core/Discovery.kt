package app.pixelferry.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Lets the computer find the Pixel on the Wi-Fi by itself (no address to type).
 * The computer broadcasts "PIXELFERRY-DISCOVER 1"; the Pixel answers
 * "PIXELFERRY 1 <https port> <certificate pin>". The answer carries no secret: the computer
 * still checks the pin over TLS, so a fake answer can't trick it.
 */
class Discovery(private val httpsPort: () -> Int, private val pin: String, private val udpPort: Int = DEFAULT_PORT) {
    private var socket: DatagramSocket? = null
    @Volatile private var running = false

    fun start() {
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(udpPort)) }
        socket = s; running = true
        Thread({
            val buf = ByteArray(256)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try { s.receive(pkt) } catch (_: SocketException) { break }
                val msg = String(pkt.data, 0, pkt.length, Charsets.US_ASCII).trim()
                if (msg != QUERY) continue
                val reply = "PIXELFERRY 1 ${httpsPort()} $pin".toByteArray(Charsets.US_ASCII)
                try { s.send(DatagramPacket(reply, reply.size, pkt.socketAddress)) } catch (_: Exception) {}
            }
        }, "pf-discovery").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; socket?.close(); socket = null }

    companion object {
        const val DEFAULT_PORT = 8445
        const val QUERY = "PIXELFERRY-DISCOVER 1"
    }
}
