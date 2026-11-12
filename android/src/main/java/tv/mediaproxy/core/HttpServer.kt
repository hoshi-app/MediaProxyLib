package tv.mediaproxy.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Минимальный HTTP/1.1 на loopback: GET/HEAD, keep-alive, всегда Content-Length (или close-delimited),
 * Range/206/416, поток на соединение, лимит соединений. Chunked не нужен: длину знаем.
 */
class HttpServer(
    private val host: String = "127.0.0.1",
    private val maxConnections: Int = 32,
    private val idleTimeoutMs: Long = 60_000,
    private val handler: (method: String, path: String, range: String?) -> HttpReply,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var boundPort: Int = 0
        private set

    private val permits = Semaphore(maxConnections)

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(host), 0), 64)
        boundPort = socket.localPort
        serverSocket = socket
        running = true
        acceptThread = thread(name = "mediaproxy.http.accept", isDaemon = true) { acceptLoop(socket) }
        Log.line("http", "слушаю $host:$boundPort")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!running) return
                continue
            }
            if (!permits.tryAcquire()) {
                writeTooMany(client)
                continue
            }
            thread(name = "mediaproxy.http.conn", isDaemon = true) {
                try {
                    serve(client)
                } catch (_: IOException) {
                } finally {
                    permits.release()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = idleTimeoutMs.toInt()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        var keepAlive = true
        while (keepAlive) {
            val request = readRequest(input) ?: return
            keepAlive = request.keepAlive
            val reply = handler(request.method, request.path, request.range)
            if (!writeReply(socket, input, output, request, reply)) return
            if (reply.length < 0) return
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val range: String?,
        val keepAlive: Boolean,
    )

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input, MAX_LINE) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") return null
        var path = parts[1].substringBefore('?')
        // absolute-form (RFC 7230 §5.3.2): некоторые клиенты шлют полный URL вместо пути.
        // Без нормализации такой запрос уходил бы в 404, хотя ресурс есть.
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            path = runCatching { java.net.URI(path).path ?: path }.getOrDefault(path)
        }

        var range: String? = null
        var connection: String? = null
        var headerCount = 0
        while (true) {
            val line = readLine(input, MAX_LINE) ?: return null
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "range" -> range = value
                "connection" -> connection = value.lowercase()
            }
        }
        return Request(method, path, range, connection != "close")
    }

    private fun writeReply(socket: Socket, input: InputStream, output: OutputStream, request: Request, reply: HttpReply): Boolean {
        var status = reply.status
        var length = reply.length
        var offset = 0L
        var count = length
        val headers = LinkedHashMap(reply.headers)

        if (!reply.rangeHandled && length >= 0) {
            when (val range = Http.parseRange(request.range, length)) {
                is RangeResult.Valid -> {
                    status = 206
                    offset = range.start
                    count = range.end - range.start + 1
                    headers["Content-Range"] = "bytes ${range.start}-${range.end}/$length"
                    length = count
                }
                is RangeResult.Unsatisfiable -> {
                    val head = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    runCatching { output.write(head.toByteArray(Charsets.US_ASCII)); output.flush() }
                    return false
                }
                RangeResult.None -> Unit
            }
        }

        headers["Accept-Ranges"] = if (length >= 0) "bytes" else "none"
        headers["Connection"] = if (request.keepAlive && length >= 0) "keep-alive" else "close"
        if (length >= 0) headers["Content-Length"] = length.toString()
        reply.cacheState?.let { headers.putIfAbsent("X-MediaProxy-Cache", it) }

        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(Http.statusText[status] ?: "Unknown").append("\r\n")
            headers.forEach { (key, value) -> if (value.isNotEmpty()) append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        return try {
            output.write(head.toByteArray(Charsets.US_ASCII))
            if (request.method == "HEAD" || reply.body == null || count == 0L) {
                output.flush()
                return true
            }
            val deadline = nowMs() + maxOf(30_000L, idleTimeoutMs)
            reply.body.write(output, offset, count, deadline)
            output.flush()
            true
        } catch (e: IOException) {
            // Абортивное закрытие: SO_LINGER 0 → RST, чтобы плеер понял «тело оборвано».
            runCatching {
                socket.setSoLinger(true, 0)
                socket.close()
            }
            false
        }
    }

    private fun writeTooMany(socket: Socket) {
        runCatching {
            val body = "Too many connections".toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }
        runCatching { socket.close() }
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > max) return null
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 100
    }
}
