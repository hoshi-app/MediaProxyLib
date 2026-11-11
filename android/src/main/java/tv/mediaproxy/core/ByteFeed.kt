package tv.mediaproxy.core

/**
 * Общий буфер между загрузчиком и отдачей. Пишет в файл (кэш) или в память (pass-through).
 * Читатель ждёт `written >= N` на condition, а не EOF файла: иначе получит обрезанное тело (P-11).
 */
class ByteFeed(val file: java.io.File?) {
    private val monitor = Object()
    private var out: java.io.FileOutputStream? = null
    private var writtenBytes = 0L
    private var totalBytes = -1L
    private var responseType: String? = null
    private var failure: ProxyErrorCode? = null
    private var finished = false
    private var memory = java.io.ByteArrayOutputStream()
    private val firstBytes = java.io.ByteArrayOutputStream(64)

    @Volatile
    var finalUrl: String? = null
        private set

    @Volatile
    var etag: String? = null
        private set

    init {
        if (file != null) {
            Fs.ensureDir(file.absoluteFile.parentFile)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(0) }
            out = java.io.FileOutputStream(file, true)
        }
    }

    val written: Long
        get() = synchronized(monitor) { writtenBytes }

    val total: Long
        get() = synchronized(monitor) { totalBytes }

    val contentType: String?
        get() = synchronized(monitor) { responseType }

    val error: ProxyErrorCode?
        get() = synchronized(monitor) { failure }

    val isFinished: Boolean
        get() = synchronized(monitor) { finished }

    val prefix: ByteArray
        get() = synchronized(monitor) { firstBytes.toByteArray() }

    fun setMeta(total: Long, contentType: String?, finalUrl: String?, etag: String?) {
        synchronized(monitor) {
            totalBytes = total
            responseType = contentType
            this.finalUrl = finalUrl
            this.etag = etag
            monitor.notifyAll()
        }
    }

    @Throws(java.io.IOException::class)
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        synchronized(monitor) {
            if (failure != null) return
            val room = 64 - firstBytes.size()
            if (room > 0) firstBytes.write(data, offset, minOf(room, length))
            val sink = out
            if (sink != null) sink.write(data, offset, length) else memory.write(data, offset, length)
            writtenBytes += length
            monitor.notifyAll()
        }
    }

    fun finish() {
        synchronized(monitor) {
            finished = true
            monitor.notifyAll()
        }
        runCatching { out?.flush() }
        runCatching { out?.close() }
    }

    fun fail(code: ProxyErrorCode) {
        synchronized(monitor) {
            if (failure == null) failure = code
            monitor.notifyAll()
        }
        runCatching { out?.flush() }
        runCatching { out?.close() }
    }

    /** Ждёт первый байт/ошибку. null — дождались; иначе код (включая таймаут). */
    fun waitForFirstBytes(timeoutMs: Long): ProxyErrorCode? {
        val deadline = nowMs() + timeoutMs
        synchronized(monitor) {
            while (writtenBytes == 0L && failure == null && !finished) {
                val left = deadline - nowMs()
                if (left <= 0) return failure ?: ProxyErrorCode.TIMEOUT_FIRST_BYTE
                waitUntil(left)
            }
            failure?.let { return it }
            if (finished && writtenBytes == 0L) return ProxyErrorCode.EMPTY_RESPONSE
            return null
        }
    }

    /** Ждёт, пока появится байт с офсетом `offset`; возвращает доступный префикс. */
    fun waitFor(offset: Long, timeoutMs: Long): Long {
        val deadline = nowMs() + timeoutMs
        synchronized(monitor) {
            while (writtenBytes <= offset && failure == null && !finished) {
                val left = deadline - nowMs()
                if (left <= 0) return writtenBytes
                waitUntil(left)
            }
            return writtenBytes
        }
    }

    fun waitForFinish(timeoutMs: Long): Boolean {
        val deadline = nowMs() + timeoutMs
        synchronized(monitor) {
            while (!finished && failure == null) {
                val left = deadline - nowMs()
                if (left <= 0) return false
                waitUntil(left)
            }
            return failure == null
        }
    }

    /** Pass-through: следующая порция из памяти. null — поток завершён. */
    fun readChunk(maxBytes: Int, timeoutMs: Long): ByteArray? {
        val deadline = nowMs() + timeoutMs
        synchronized(monitor) {
            while (memory.size() == 0 && failure == null && !finished) {
                val left = deadline - nowMs()
                if (left <= 0) return ByteArray(0)
                waitUntil(left)
            }
            failure?.let { return null }
            if (memory.size() == 0) return null
            val all = memory.toByteArray()
            val take = minOf(maxBytes, all.size)
            memory = java.io.ByteArrayOutputStream()
            if (take < all.size) memory.write(all, take, all.size - take)
            return all.copyOfRange(0, take)
        }
    }

    private fun waitUntil(ms: Long) {
        runCatching { monitor.wait(ms.coerceAtLeast(1)) }
    }
}
