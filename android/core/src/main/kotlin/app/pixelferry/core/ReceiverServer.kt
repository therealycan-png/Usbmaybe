package app.pixelferry.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * HTTPS receiver for the desktop sender (Windows / Linux).
 * Minimal HTTP/1.1 with keep-alive. Every request except /api/hello needs the token.
 */
class ReceiverServer(
    private val engine: IngestEngine,
    private val ledger: Ledger,
    private val sslContext: SSLContext,
    private val token: String,
    private val listener: Listener = object : Listener {},
    private val port: Int = 8443,
    private val bind: InetAddress? = null,
) {
    interface Listener {
        fun onStored(name: String, size: Long) {}
        fun onNeedSpace(bytes: Long) {}
        fun statusMessage(): String = "ready"
        fun isWaitingForBackup(): Boolean = false
        fun log(msg: String) {}
    }

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    /** Wrong-code attempts per sender address: one bad neighbour can't lock out your computer. */
    private class Strikes(var count: Int = 0, var lockedUntil: Long = 0)
    private val strikes = java.util.concurrent.ConcurrentHashMap<String, Strikes>()
    @Volatile var running = false; private set

    val localPort: Int get() = server?.localPort ?: -1

    fun start() {
        val ss = sslContext.serverSocketFactory.createServerSocket(port, 16, bind) as SSLServerSocket
        ss.enabledProtocols = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        server = ss
        val p = Executors.newFixedThreadPool(8)
        pool = p
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: SocketException) { break }
                p.execute { handle(s) }
            }
        }, "pf-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
    }

    private class Req(val ip: String, val method: String, val path: String, val headers: Map<String, String>, val body: InputStream, val length: Long)

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15_000 // handshake + first request must come quickly
            s.tcpNoDelay = true
            val ip = s.inetAddress?.hostAddress ?: "?"
            try { (s as? javax.net.ssl.SSLSocket)?.startHandshake() } catch (e: Exception) { return }
            s.soTimeout = 60_000 // a live upload sends data far more often than this
            val input = BufferedInputStream(s.getInputStream(), 1 shl 16)
            val out = s.getOutputStream().buffered(1 shl 16)
            try {
                while (running) {
                    val req = readRequest(input, ip) ?: return
                    val keep = route(req, out)
                    out.flush()
                    if (!keep) return
                }
            } catch (e: Exception) {
                if (e !is EOFException && e !is SocketException) listener.log("conn error: ${e.message}")
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IllegalStateException("header too long")
        }
    }

    private fun readRequest(i: InputStream, ip: String): Req? {
        var line = readLine(i) ?: return null
        var blank = 0
        while (line.isEmpty()) { if (++blank > 4) return null; line = readLine(i) ?: return null }
        val parts = line.split(' ')
        if (parts.size < 2) throw IllegalStateException("bad request line")
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(i) ?: throw EOFException()
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            if (headers.size > 64) throw IllegalStateException("too many headers")
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        return Req(ip, parts[0], parts[1].substringBefore('?'), headers, BoundedInput(i, len), len)
    }

    private fun respond(out: OutputStream, code: Int, body: String, keep: Boolean = true, type: String = "application/json") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"
            422 -> "Unprocessable"; 429 -> "Too Many"; 507 -> "Insufficient Storage"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            (if (keep) "Connection: keep-alive\r\n" else "Connection: close\r\n") + "\r\n").toByteArray())
        out.write(bytes)
    }

    private fun dec(s: String?) = s?.let { URLDecoder.decode(it, "UTF-8") }

    private fun locked(req: Req) = (strikes[req.ip]?.lockedUntil ?: 0) > System.currentTimeMillis()

    private fun authed(req: Req): Boolean {
        if (locked(req)) return false
        val h = req.headers["authorization"] ?: ""
        val ok = h.startsWith("Bearer ") && Pairing.constantTimeEquals(h.removePrefix("Bearer ").trim(), token)
        val st = strikes.getOrPut(req.ip) { Strikes() }
        synchronized(st) {
            if (ok) st.count = 0
            else if (++st.count >= 8) { st.lockedUntil = System.currentTimeMillis() + 5 * 60_000; st.count = 0 }
        }
        return ok
    }

    private fun drain(req: Req) { (req.body as BoundedInput).drain() }

    /** @return keep connection alive */
    private fun route(req: Req, out: OutputStream): Boolean {
        if (req.path == "/api/hello") { drain(req); respond(out, 200, """{"app":"PixelFerry","v":1}"""); return true }
        if (!authed(req)) {
            respond(out, if (locked(req)) 429 else 401, """{"error":"bad pairing code"}""", keep = false)
            return false
        }
        return when (req.method to req.path) {
            "GET" to "/api/status" -> { drain(req); respond(out, 200, statusJson()); true }
            "POST" to "/api/check" -> { check(req, out); true }
            "PUT" to "/api/upload" -> upload(req, out)
            "POST" to "/api/deletable" -> { deletable(req, out); true }
            "POST" to "/api/mark-deleted" -> { markDeleted(req, out); true }
            else -> { drain(req); respond(out, 404, """{"error":"not found"}"""); true }
        }
    }

    private fun statusJson(): String {
        val free = engineFree()
        return """{"free":$free,"reserve":${engine.reserveBytes},"waiting":${listener.isWaitingForBackup()},"message":${json(listener.statusMessage())}}"""
    }

    private fun engineFree(): Long = try { engine.freeBytes() } catch (_: Exception) { -1 }

    private fun bodyText(req: Req): String {
        if (req.length > 64L * 1024 * 1024) throw IllegalStateException("body too big")
        return req.body.readBytes().toString(Charsets.UTF_8)
    }

    /** Computers live in their own namespace: they can never touch a USB phone's records. */
    private fun device(req: Req): String {
        val raw = dec(req.headers["x-device-id"]) ?: throw IllegalStateException("no device id")
        require(raw.length in 4..80 && raw.all { it.isLetterOrDigit() || it == '-' }) { "bad device id" }
        return "pc:$raw"
    }

    private fun check(req: Req, out: OutputStream) {
        val dev = device(req)
        val sb = StringBuilder()
        for (line in bodyText(req).lineSequence()) {
            if (line.isBlank()) continue
            val key = dec(line.substringBefore('\t'))!!
            val item = engine.alreadyHave(dev, key)
            if (item != null) sb.append(line.substringBefore('\t')).append('\t').append(item.state.name).append('\n')
        }
        respond(out, 200, sb.toString(), type = "text/plain")
    }

    private fun upload(req: Req, out: OutputStream): Boolean {
        val h = req.headers
        val dev = device(req)
        val key = dec(h["x-key"]) ?: return bad(out, req)
        val name = dec(h["x-name"]) ?: return bad(out, req)
        val size = h["x-size"]?.toLongOrNull() ?: return bad(out, req)
        val sha = h["x-sha256"]?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return bad(out, req)
        val mtime = h["x-mtime"]?.toLongOrNull() ?: 0L
        val owner = dec(h["x-owner"]) ?: "Computer"
        val label = dec(h["x-device"]) ?: "PC"
        if (size != req.length) return bad(out, req)
        if (!MediaTypes.isMedia(name)) { drain(req); respond(out, 422, """{"error":"not a photo/video"}"""); return true }

        val profile = DeviceProfile(dev, owner, label).also { if (ledger.device(dev) != it) ledger.saveDevice(it) }
        engine.alreadyHave(dev, key)?.let { drain(req); respond(out, 200, """{"status":"already","state":"${it.state}"}"""); return true }
        if (!engine.hasRoomFor(size)) {
            listener.onNeedSpace(size)
            respond(out, 507, """{"error":"pixel full, waiting for Google Photos backup"}""", keep = false)
            return false
        }
        return try {
            val r = engine.ingest(profile, key, name, size, mtime, sha) { tmp: File ->
                tmp.outputStream().use { fo -> copyExact(req.body, fo, size) }
            }
            when (r) {
                is IngestResult.Stored -> { listener.onStored(name, size); respond(out, 200, """{"status":"verified"}""") }
                is IngestResult.Duplicate -> respond(out, 200, """{"status":"duplicate"}""")
                is IngestResult.AlreadyHave -> respond(out, 200, """{"status":"already"}""")
            }
            true
        } catch (e: NotEnoughSpace) {
            listener.onNeedSpace(size); respond(out, 507, """{"error":"pixel full"}""", keep = false); false
        } catch (e: VerifyFailed) {
            listener.log(e.message ?: "verify failed")
            respond(out, 409, """{"error":${json(e.message ?: "verify failed")}}""", keep = false); false
        }
    }

    private fun bad(out: OutputStream, req: Req): Boolean {
        respond(out, 422, """{"error":"bad upload headers"}""", keep = false); return false
    }

    private fun deletable(req: Req, out: OutputStream) {
        val dev = device(req); drain(req)
        val sb = StringBuilder()
        ledger.deletableFor(dev).forEach {
            sb.append(java.net.URLEncoder.encode(it.itemKey, "UTF-8").replace("+", "%20")).append('\t').append(it.sha256).append('\n')
        }
        respond(out, 200, sb.toString(), type = "text/plain")
    }

    private fun markDeleted(req: Req, out: OutputStream) {
        val dev = device(req)
        var n = 0
        for (line in bodyText(req).lineSequence()) {
            if (line.isBlank()) continue
            val item = ledger.find(dev, dec(line)!!) ?: continue
            if (ledger.isSafeToDeleteFromSource(item)) { ledger.setState(item.id, ItemState.SOURCE_DELETED); n++ }
        }
        respond(out, 200, """{"marked":$n}""")
    }

    companion object {
        fun json(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code)); else -> append(c)
            }
            append('"')
        }

        fun copyExact(i: InputStream, o: OutputStream, size: Long) {
            val buf = ByteArray(1 shl 20)
            var left = size
            while (left > 0) {
                val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("sender disconnected")
                o.write(buf, 0, n); left -= n
            }
        }
    }
}

/** Never lets a handler read past the request body into the next request. */
class BoundedInput(private val inner: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int { if (left <= 0) return -1; val c = inner.read(); if (c >= 0) left--; return c }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
    fun drain() { val buf = ByteArray(65536); while (read(buf) >= 0) {} }
}
