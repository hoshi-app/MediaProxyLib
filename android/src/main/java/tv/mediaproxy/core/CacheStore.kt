package tv.mediaproxy.core

import java.io.File
import java.io.IOException

/**
 * Segment disk cache: append-only journal, reconciliation with files at startup, whole-stream LRU,
 * and refcounts for deferred deletion. No tmp-to-rename per segment.
 */
class CacheStore(
    private val root: File,
    private val config: () -> ProxyConfig,
) {
    data class Entry(
        val index: Int,
        var total: Long = -1,
        var prefix: Long = 0,
        var complete: Boolean = false,
        var failedUntil: Long = 0,
        var contentType: String? = null,
        /** ETag of the last origin response, needed for `If-Range` on a prefix resume. */
        var etag: String? = null,
        var downloading: Boolean = false,
        var feed: ByteFeed? = null,
        val file: File,
        var lastAccess: Long = 0,
    )

    inner class Stream(
        val key: String,
        val cacheKey: String,
        val fingerprint: String,
        val dir: File,
        @Volatile var lastAccess: Long,
    ) {
        val entries = HashMap<Int, Entry>()
        var refs: Int = 0
        var pendingDelete: Boolean = false
    }

    private val lock = Object()
    private val streams = HashMap<String, Stream>()
    private var evictedStreams = 0L

    init {
        Fs.ensureDir(root)
    }

    // MARK: - streams

    fun openStream(cacheKey: String, fingerprint: String, now: Long): Stream {
        synchronized(lock) {
            val key = Fnv.hex(cacheKey)
            val existing = streams[key]
            if (existing != null) {
                if (existing.fingerprint == fingerprint) {
                    existing.lastAccess = now
                    return existing
                }
                streams.remove(key)
                existing.dir.deleteRecursively()
            }
            val dir = File(root, key)
            Fs.ensureDir(dir)
            val stream = Stream(key, cacheKey, fingerprint, dir, now)
            loadLocked(stream)
            streams[key] = stream
            return stream
        }
    }

    fun stream(cacheKey: String): Stream? = synchronized(lock) { streams[Fnv.hex(cacheKey)] }

    fun touch(stream: Stream, now: Long) {
        synchronized(lock) {
            stream.lastAccess = now
            stream.entries.values.forEach { it.lastAccess = now }
        }
    }

    fun retain(stream: Stream) {
        synchronized(lock) { stream.refs++ }
    }

    fun release(stream: Stream) {
        synchronized(lock) {
            stream.refs = (stream.refs - 1).coerceAtLeast(0)
            if (stream.refs == 0 && stream.pendingDelete) {
                streams.remove(stream.key)
                stream.dir.deleteRecursively()
            }
        }
    }

    // MARK: - entries

    fun entry(stream: Stream, index: Int): Entry? = synchronized(lock) { stream.entries[index]?.copy() }

    fun ensureEntry(stream: Stream, index: Int, now: Long): Entry {
        synchronized(lock) {
            stream.entries[index]?.let { return it.copy() }
            val entry = Entry(index = index, file = fileURL(stream, index), lastAccess = now)
            stream.entries[index] = entry
            journalLocked(stream, entry)
            return entry.copy()
        }
    }

    fun update(stream: Stream, index: Int, mutate: (Entry) -> Unit) {
        synchronized(lock) {
            val entry = stream.entries[index] ?: return
            mutate(entry)
            journalLocked(stream, entry)
        }
    }

    fun removeEntry(stream: Stream, index: Int) {
        synchronized(lock) {
            stream.entries.remove(index)?.file?.delete()
            rewriteJournalLocked(stream)
        }
    }

    fun fileURL(stream: Stream, index: Int): File = File(stream.dir, "s%05d.bin".format(index))

    fun totalBytes(): Long = synchronized(lock) { streams.values.sumOf { streamBytesLocked(it) } }

    fun streamBytes(stream: Stream): Long = synchronized(lock) { streamBytesLocked(stream) }

    private fun streamBytesLocked(stream: Stream): Long =
        stream.entries.values.sumOf { it.prefix.coerceAtLeast(0) }

    // MARK: - eviction

    /** Frees room for `required` bytes. Returns false when there is no room; the caller then uses pass-through. */
    fun evictIfNeeded(required: Long, protecting: Set<String>, now: Long): Boolean {
        synchronized(lock) {
            val cfg = config()
            if (cfg.maxCacheBytes <= 0) return false
            if (Fs.freeDiskBytes(root) < cfg.minFreeDiskBytes) return false
            val softLimit = cfg.maxCacheBytes * 9 / 10
            if (totalBytesLocked() + required <= softLimit) return true

            val candidates = streams.values
                .filter { it.cacheKey !in protecting && it.refs == 0 && !it.pendingDelete }
                .sortedBy { it.lastAccess }
            for (candidate in candidates) {
                if (totalBytesLocked() + required <= softLimit) return true
                candidate.pendingDelete = true
                streams.remove(candidate.key)
                candidate.dir.deleteRecursively()
                evictedStreams++
            }
            return totalBytesLocked() + required <= cfg.maxCacheBytes
        }
    }

    /** Streams evicted since start, for `getStats().evictions`. */
    fun evictedCount(): Long = synchronized(lock) { evictedStreams }

    private fun totalBytesLocked(): Long = streams.values.sumOf { streamBytesLocked(it) }

    // MARK: - clear

    fun clear(cacheKey: String? = null): Long {
        synchronized(lock) {
            var freed = 0L
            val targets = if (cacheKey != null) {
                streams[Fnv.hex(cacheKey)]?.let { listOf(it) } ?: emptyList()
            } else {
                streams.values.toList()
            }
            for (stream in targets) {
                freed += streamBytesLocked(stream)
                // Always drop entries; otherwise after clear the server would serve complete segments
                // whose files are gone. FileBody fails and the player sees a truncated body.
                stream.entries.clear()
                streams.remove(stream.key)
                stream.dir.deleteRecursively()
            }
            return freed
        }
    }

    // MARK: - reconciliation

    private fun loadLocked(stream: Stream) {
        val journal = File(stream.dir, "journal.log")
        val lines = Fs.readLines(journal)
        // The header is the only way to tell the same content from another playlist with the same
        // cacheKey after a process restart. Without the fingerprint check we would serve ready
        // segments of the wrong content.
        // In `H|2` the fingerprint comes before cacheKey because app cacheKeys contain `|`
        // (`t1|p-x|e2|q720`) and cannot be split.
        val header = lines.lastOrNull { it.startsWith("H|") }
        val segments = lines.any { it.startsWith("S|") }
        if (segments && !headerMatches(header, stream)) {
            wipeLocked(stream)
            return
        }
        // Append-only journal: the last record per index wins, and each record is checked against its file once.
        val latest = HashMap<Int, Entry>()
        for (line in lines) {
            val parts = line.split("|")
            if (parts.firstOrNull() != "S" || parts.size < 8) continue
            val index = parts[1].toIntOrNull() ?: continue
            latest[index] = Entry(
                index = index,
                total = parts[2].toLongOrNull() ?: -1,
                prefix = parts[3].toLongOrNull() ?: 0,
                complete = parts[4] == "1",
                contentType = parts[5].ifEmpty { null },
                file = fileURL(stream, index),
                lastAccess = parts[6].toLongOrNull() ?: 0,
            )
        }
        for ((index, entry) in latest) {
            val size = Fs.size(entry.file)
            if (size == 0L && !entry.complete) continue
            if (entry.prefix > size) entry.prefix = size
            if (size > entry.prefix) Fs.truncate(entry.file, entry.prefix)
            if (entry.total >= 0 && entry.prefix < entry.total) entry.complete = false
            if (entry.total < 0) entry.complete = false
            if (entry.prefix > 0 || entry.complete) stream.entries[index] = entry
        }
        val known = stream.entries.keys.map { fileURL(stream, it).name }.toSet()
        stream.dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") && file.name !in known) file.delete()
        }
        rewriteJournalLocked(stream)
    }

    private fun rewriteJournalLocked(stream: Stream) {
        val journal = File(stream.dir, "journal.log")
        val text = buildString {
            append("H|2|").append(stream.fingerprint).append('|').append(stream.cacheKey).append('\n')
            stream.entries.values.sortedBy { it.index }.forEach { e ->
                append("S|").append(e.index).append('|').append(e.total).append('|').append(e.prefix)
                    .append('|').append(if (e.complete) 1 else 0).append('|').append(e.contentType ?: "")
                    .append('|').append(e.lastAccess).append('|').append(e.index).append('\n')
            }
        }
        try {
            journal.writeText(text, Charsets.UTF_8)
        } catch (_: IOException) {
            // The journal is cache, so a write failure must not break playback.
        }
    }

    private fun headerMatches(line: String?, stream: Stream): Boolean {
        if (line == null) return false
        val parts = line.split("|")
        if (parts.size < 4 || parts[0] != "H" || parts[1] != "2") return false
        val fingerprint = parts[2]
        val cacheKey = parts.drop(3).joinToString("|")
        return fingerprint == stream.fingerprint && cacheKey == stream.cacheKey
    }

    /** Wipes an invalid directory so foreign or corrupt entries do not survive a playlist change. */
    private fun wipeLocked(stream: Stream) {
        stream.entries.clear()
        stream.dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") || file.name == "journal.log") file.delete()
        }
        rewriteJournalLocked(stream)
    }

    private fun journalLocked(stream: Stream, entry: Entry) {
        val journal = File(stream.dir, "journal.log")
        val line = "S|${entry.index}|${entry.total}|${entry.prefix}|${if (entry.complete) 1 else 0}|" +
            "${entry.contentType ?: ""}|${entry.lastAccess}|${entry.index}"
        try {
            Fs.appendLine(journal, line)
        } catch (_: IOException) {
        }
    }
}
