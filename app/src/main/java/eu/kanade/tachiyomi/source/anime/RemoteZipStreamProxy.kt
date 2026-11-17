package eu.kanade.tachiyomi.source.anime

import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min
import okhttp3.Response as OkHttpResponse

/**
 * Serves individual video entries inside remote HTTP ZIP packs over a loopback HTTP endpoint
 * so ExoPlayer can play MoviesMod Batch/Zip packs without downloading the whole archive first.
 */
class RemoteZipStreamProxy : NanoHTTPD("127.0.0.1", 0) {

    data class ZipVideoEntry(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    data class ZipInspectionResult(
        val workingUrl: String,
        val totalSize: Long,
        val entries: List<ZipVideoEntry>,
    )

    private data class RegisteredEntry(
        val candidateUrls: List<String>,
        val headers: Headers,
        val client: OkHttpClient,
        val entry: ZipVideoEntry,
        val cachedDataOffsets: MutableMap<String, Long> = mutableMapOf(),
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, RegisteredEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RegisteredEntry>?): Boolean {
            return size > MAX_REGISTERED_ENTRIES
        }
    }

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    fun registerEntry(
        candidateUrls: List<String>,
        headers: Headers,
        client: OkHttpClient,
        entry: ZipVideoEntry,
    ): String {
        val cleanUrls = candidateUrls.distinct().filter { it.isNotBlank() }
        require(cleanUrls.isNotEmpty()) { "Candidate URLs must not be empty" }
        synchronized(lock) {
            if (!wasStarted()) {
                start(SOCKET_READ_TIMEOUT, true)
            }
            val token = UUID.randomUUID().toString()
            entries[token] = RegisteredEntry(
                candidateUrls = cleanUrls,
                headers = headers,
                client = client,
                entry = entry,
            )
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            return "http://127.0.0.1:$listeningPort$ENTRY_PATH?token=$encodedToken"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != ENTRY_PATH) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        val token = session.parms["token"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            MIME_PLAINTEXT,
            "Missing token",
        )
        val reg = synchronized(lock) { entries[token] } ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Expired or unknown ZIP stream token",
        )

        val mime = mimeTypeForVideoName(reg.entry.name)
        val rangeHeader = session.headers["range"]

        return when (reg.entry.compressionMethod) {
            METHOD_STORED -> serveStoredEntry(session, reg, mime, rangeHeader)
            METHOD_DEFLATE -> serveDeflateEntry(session, reg, mime, rangeHeader)
            else -> newFixedLengthResponse(
                Response.Status.UNSUPPORTED_MEDIA_TYPE,
                MIME_PLAINTEXT,
                "Unsupported ZIP compression method: ${reg.entry.compressionMethod}",
            )
        }
    }

    private fun serveStoredEntry(
        session: IHTTPSession,
        reg: RegisteredEntry,
        mime: String,
        rangeHeader: String?,
    ): Response {
        val total = reg.entry.uncompressedSize
        val range = parseByteRange(rangeHeader, total)
        if (range == null) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                addHeader("Content-Range", "bytes */$total")
            }
        }
        val (start, end) = range
        val sliceLength = end - start + 1L
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK

        if (session.method == Method.HEAD) {
            return newFixedLengthResponse(status, mime, ByteArrayInputStream(ByteArray(0)), 0L).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", sliceLength.toString())
                if (status == Response.Status.PARTIAL_CONTENT) {
                    addHeader("Content-Range", "bytes $start-$end/$total")
                }
            }
        }

        val stream = try {
            openUpstreamRangeStream(
                reg = reg,
                relativeStart = start,
                relativeEnd = end,
            )
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to open STORED ZIP entry ${reg.entry.name}" }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Upstream error")
        }

        return newFixedLengthResponse(status, mime, stream, sliceLength).apply {
            addHeader("Accept-Ranges", "bytes")
            if (status == Response.Status.PARTIAL_CONTENT) {
                addHeader("Content-Range", "bytes $start-$end/$total")
            }
        }
    }

    private fun serveDeflateEntry(
        session: IHTTPSession,
        reg: RegisteredEntry,
        mime: String,
        rangeHeader: String?,
    ): Response {
        val requestedStart = parseDeflateStartOffset(rangeHeader, reg.entry.uncompressedSize)
        if (requestedStart == null || requestedStart > MAX_DEFLATE_SKIP_BYTES) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                addHeader("Accept-Ranges", "none")
            }
        }

        if (session.method == Method.HEAD) {
            // Omit Content-Length and disable Range so MatroskaExtractor streams sequentially without EOF seeks.
            return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(ByteArray(0)), 0L).apply {
                addHeader("Accept-Ranges", "none")
            }
        }

        val compressedEnd = (reg.entry.compressedSize - 1L).coerceAtLeast(0L)
        val rawStream = try {
            openUpstreamRangeStream(
                reg = reg,
                relativeStart = 0L,
                relativeEnd = compressedEnd,
            )
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to open DEFLATE ZIP entry ${reg.entry.name}" }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Upstream error")
        }

        val inflater = Inflater(true)
        val inflateStream = ClosingInflaterInputStream(rawStream, inflater)
        if (requestedStart > 0L) {
            try {
                skipFully(inflateStream, requestedStart)
            } catch (e: IOException) {
                inflateStream.close()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Seek error")
            }
        }

        // Chunked response keeps Content-Length unset so ExoPlayer starts playback immediately from frame 1.
        return newChunkedResponse(Response.Status.OK, mime, inflateStream).apply {
            addHeader("Accept-Ranges", "none")
        }
    }

    private fun openUpstreamRangeStream(
        reg: RegisteredEntry,
        relativeStart: Long,
        relativeEnd: Long,
    ): InputStream {
        var lastError: IOException? = null
        for (url in reg.candidateUrls) {
            try {
                val dataOffset = synchronized(reg.cachedDataOffsets) {
                    reg.cachedDataOffsets[url]
                }
                    ?: resolveLocalHeaderDataOffset(
                        reg.client,
                        reg.headers,
                        url,
                        reg.entry.localHeaderOffset,
                    ).also { off ->
                        synchronized(reg.cachedDataOffsets) {
                            reg.cachedDataOffsets[url] = off
                        }
                    }
                val upstreamStart = dataOffset + relativeStart
                val upstreamEnd = dataOffset + relativeEnd
                val req = Request.Builder()
                    .url(url)
                    .headers(reg.headers)
                    .header("Range", "bytes=$upstreamStart-$upstreamEnd")
                    .get()
                    .build()
                val response = reg.client.newCall(req).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IOException("HTTP $code from $url")
                }
                val bodyStream = response.body.byteStream()
                if (response.code == 200 && upstreamStart > 0L) {
                    response.close()
                    throw IOException("Server ignored Range request for $url")
                }
                val maxBytes = (upstreamEnd - upstreamStart + 1L).coerceAtLeast(0L)
                return BoundedResponseInputStream(bodyStream, response, maxBytes)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All mirror URLs failed for ${reg.entry.name}")
    }

    private class BoundedResponseInputStream(
        delegate: InputStream,
        private val response: OkHttpResponse,
        private var remaining: Long,
    ) : FilterInputStream(delegate) {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val maxRead = min(len.toLong(), remaining).toInt()
            val n = super.read(b, off, maxRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private class ClosingInflaterInputStream(
        private val raw: InputStream,
        private val inflater: Inflater,
    ) : InflaterInputStream(raw, inflater, INFLATE_BUFFER_BYTES) {
        override fun close() {
            try {
                super.close()
            } finally {
                raw.close()
                inflater.end()
            }
        }
    }

    companion object {
        private const val ENTRY_PATH = "/zip-entry"
        private const val MAX_REGISTERED_ENTRIES = 128
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8
        private const val INFLATE_BUFFER_BYTES = 65_536
        private const val EOCD_TAIL_BYTES = 65_536L
        private const val MAX_DEFLATE_SKIP_BYTES = 8L * 1024L * 1024L

        private const val SIG_EOCD = 0x06054b50
        private const val SIG_ZIP64_LOCATOR = 0x07064b50
        private const val SIG_ZIP64_EOCD = 0x06064b50
        private const val SIG_CENTRAL_DIR = 0x02014b50
        private const val SIG_LOCAL_HEADER = 0x04034b50

        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "webm", "avi", "ts", "mov", "flv", "m2ts")
        private val NUMBER_TOKEN_REGEX = Regex("\\d+|\\D+")

        val shared: RemoteZipStreamProxy by lazy { RemoteZipStreamProxy() }

        fun inspectRemoteZip(
            client: OkHttpClient,
            headers: Headers,
            candidateUrls: List<String>,
        ): ZipInspectionResult {
            var lastError: Exception? = null
            for (url in candidateUrls.distinct().filter { it.isNotBlank() }) {
                try {
                    val totalSize = probeZipTotalSize(client, headers, url)
                    if (totalSize < 22L) continue
                    val tailStart = max(0L, totalSize - EOCD_TAIL_BYTES)
                    val tail = fetchRangeBytes(client, headers, url, tailStart, totalSize - 1L)
                    val eocdOffset = findEocdOffset(tail) ?: continue
                    val eocd = ByteBuffer.wrap(tail, eocdOffset, tail.size - eocdOffset).order(ByteOrder.LITTLE_ENDIAN)
                    eocd.position(eocdOffset + 4) // signature
                    val diskNum = eocd.short.toInt() and 0xFFFF
                    val cdDisk = eocd.short.toInt() and 0xFFFF
                    if (diskNum != 0 || cdDisk != 0) continue
                    eocd.short // disk entries
                    val totalEntries16 = eocd.short.toInt() and 0xFFFF
                    var cdSize = eocd.int.toLong() and 0xFFFFFFFFL
                    var cdOffset = eocd.int.toLong() and 0xFFFFFFFFL

                    if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || totalEntries16 == 0xFFFF) {
                        val zip64 = readZip64CdBounds(client, headers, url, tail, tailStart, eocdOffset)
                        if (zip64 != null) {
                            cdSize = zip64.first
                            cdOffset = zip64.second
                        }
                    }
                    if (cdSize <= 0L || cdOffset < 0L || cdOffset + cdSize > totalSize) continue

                    val cdBytes = if (cdOffset >= tailStart && cdOffset + cdSize <= totalSize) {
                        val relStart = (cdOffset - tailStart).toInt()
                        tail.copyOfRange(relStart, relStart + cdSize.toInt())
                    } else {
                        fetchRangeBytes(client, headers, url, cdOffset, cdOffset + cdSize - 1L)
                    }

                    val entries = parseCentralDirectory(cdBytes)
                        .sortedWith { a, b -> compareNatural(a.name, b.name) }
                    if (entries.isNotEmpty()) {
                        return ZipInspectionResult(
                            workingUrl = url,
                            totalSize = totalSize,
                            entries = entries,
                        )
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw IOException("Unable to inspect remote ZIP archive", lastError)
        }

        private fun probeZipTotalSize(client: OkHttpClient, headers: Headers, url: String): Long {
            val rangeReq = Request.Builder()
                .url(url)
                .headers(headers)
                .header("Range", "bytes=0-3")
                .get()
                .build()
            client.newCall(rangeReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} probing $url")
                }
                val headBytes = ByteArray(4)
                val stream = resp.body.byteStream()
                var readCount = 0
                while (readCount < headBytes.size) {
                    val n = stream.read(headBytes, readCount, headBytes.size - readCount)
                    if (n < 0) break
                    readCount += n
                }
                if (readCount < 2 || headBytes[0] != 0x50.toByte() || headBytes[1] != 0x4B.toByte()) {
                    throw IOException("Upstream $url is not a ZIP archive")
                }
                val contentRange = resp.header("Content-Range")
                val totalFromRange = contentRange?.substringAfterLast('/', "")?.toLongOrNull()
                if (totalFromRange != null && totalFromRange > 0L) return totalFromRange
                val len = resp.header("Content-Length")?.toLongOrNull()
                if (resp.code == 200 && len != null && len > 0L) return len
            }

            val headReq = Request.Builder()
                .url(url)
                .headers(headers)
                .head()
                .build()
            client.newCall(headReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HEAD HTTP ${resp.code} for $url")
                return resp.header("Content-Length")?.toLongOrNull()
                    ?: throw IOException("Missing Content-Length for $url")
            }
        }

        private fun fetchRangeBytes(
            client: OkHttpClient,
            headers: Headers,
            url: String,
            start: Long,
            end: Long,
        ): ByteArray {
            val req = Request.Builder()
                .url(url)
                .headers(headers)
                .header("Range", "bytes=$start-$end")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} reading range $start-$end")
                if (resp.code == 200 && start > 0L) {
                    throw IOException("Server ignored Range request for $url")
                }
                val stream = resp.body.byteStream()
                val expected = (end - start + 1L).toInt()
                val out = ByteArray(expected)
                var readTotal = 0
                while (readTotal < expected) {
                    val n = stream.read(out, readTotal, expected - readTotal)
                    if (n < 0) break
                    readTotal += n
                }
                return if (readTotal == expected) out else out.copyOf(readTotal)
            }
        }

        private fun resolveLocalHeaderDataOffset(
            client: OkHttpClient,
            headers: Headers,
            url: String,
            localHeaderOffset: Long,
        ): Long {
            val lfh = fetchRangeBytes(client, headers, url, localHeaderOffset, localHeaderOffset + 29L)
            if (lfh.size < 30) throw IOException("Truncated local file header at $localHeaderOffset")
            val buf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != SIG_LOCAL_HEADER) {
                throw IOException("Invalid local file header signature at $localHeaderOffset")
            }
            val fileNameLen = buf.getShort(26).toInt() and 0xFFFF
            val extraLen = buf.getShort(28).toInt() and 0xFFFF
            return localHeaderOffset + 30L + fileNameLen + extraLen
        }

        internal fun findEocdOffset(tail: ByteArray): Int? {
            if (tail.size < 22) return null
            val minPos = max(0, tail.size - 22 - 65_535)
            for (pos in (tail.size - 22) downTo minPos) {
                if (tail[pos] == 0x50.toByte() &&
                    tail[pos + 1] == 0x4B.toByte() &&
                    tail[pos + 2] == 0x05.toByte() &&
                    tail[pos + 3] == 0x06.toByte()
                ) {
                    val commentLen = ((tail[pos + 21].toInt() and 0xFF) shl 8) or (tail[pos + 20].toInt() and 0xFF)
                    if (pos + 22 + commentLen == tail.size) return pos
                }
            }
            return null
        }

        private fun readZip64CdBounds(
            client: OkHttpClient,
            headers: Headers,
            url: String,
            tail: ByteArray,
            tailStart: Long,
            eocdOffsetInTail: Int,
        ): Pair<Long, Long>? {
            val locatorPos = eocdOffsetInTail - 20
            if (locatorPos < 0) return null
            val locBuf = ByteBuffer.wrap(tail, locatorPos, 20).order(ByteOrder.LITTLE_ENDIAN)
            if (locBuf.int != SIG_ZIP64_LOCATOR) return null
            locBuf.int // disk with zip64 EOCD
            val z64Offset = locBuf.long
            val z64Bytes = if (z64Offset >= tailStart && z64Offset + 56 <= tailStart + tail.size) {
                val rel = (z64Offset - tailStart).toInt()
                tail.copyOfRange(rel, rel + 56)
            } else {
                fetchRangeBytes(client, headers, url, z64Offset, z64Offset + 55L)
            }
            if (z64Bytes.size < 56) return null
            val z64 = ByteBuffer.wrap(z64Bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (z64.int != SIG_ZIP64_EOCD) return null
            val cdSize = z64.getLong(40)
            val cdOffset = z64.getLong(48)
            return cdSize to cdOffset
        }

        internal fun parseCentralDirectory(cdBytes: ByteArray): List<ZipVideoEntry> {
            val result = mutableListOf<ZipVideoEntry>()
            val buf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos + 46 <= cdBytes.size) {
                if (buf.getInt(pos) != SIG_CENTRAL_DIR) break
                val flags = buf.getShort(pos + 8).toInt() and 0xFFFF
                val method = buf.getShort(pos + 10).toInt() and 0xFFFF
                var compSize = buf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                var uncompSize = buf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                val fnLen = buf.getShort(pos + 28).toInt() and 0xFFFF
                val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
                val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
                var lfhOffset = buf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

                val nameStart = pos + 46
                val extraStart = nameStart + fnLen
                val nextPos = extraStart + extraLen + commentLen
                if (nextPos > cdBytes.size) break

                val charset = if ((flags and 0x0800) != 0) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
                val name = String(cdBytes, nameStart, fnLen, charset)

                if (extraLen > 0 &&
                    (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || lfhOffset == 0xFFFFFFFFL)
                ) {
                    var ex = extraStart
                    val exEnd = extraStart + extraLen
                    while (ex + 4 <= exEnd) {
                        val headerId = buf.getShort(ex).toInt() and 0xFFFF
                        val dataSize = buf.getShort(ex + 2).toInt() and 0xFFFF
                        var fieldPos = ex + 4
                        if (headerId == 0x0001 && fieldPos + dataSize <= exEnd) {
                            if (uncompSize == 0xFFFFFFFFL && fieldPos + 8 <= exEnd) {
                                uncompSize = buf.getLong(fieldPos)
                                fieldPos += 8
                            }
                            if (compSize == 0xFFFFFFFFL && fieldPos + 8 <= exEnd) {
                                compSize = buf.getLong(fieldPos)
                                fieldPos += 8
                            }
                            if (lfhOffset == 0xFFFFFFFFL && fieldPos + 8 <= exEnd) {
                                lfhOffset = buf.getLong(fieldPos)
                            }
                            break
                        }
                        ex += 4 + dataSize
                    }
                }

                val ext = name.substringAfterLast('.', "").lowercase()
                if (!name.endsWith("/") &&
                    !name.contains("__MACOSX/") &&
                    uncompSize > 0L &&
                    (method == METHOD_STORED || method == METHOD_DEFLATE) &&
                    ext in VIDEO_EXTENSIONS
                ) {
                    result += ZipVideoEntry(
                        name = name,
                        compressionMethod = method,
                        compressedSize = compSize,
                        uncompressedSize = uncompSize,
                        localHeaderOffset = lfhOffset,
                    )
                }

                pos = nextPos
            }
            return result
        }

        private fun parseByteRange(rangeHeader: String?, total: Long): Pair<Long, Long>? {
            if (total <= 0L) return null
            if (rangeHeader.isNullOrBlank()) return 0L to (total - 1L)
            val spec = rangeHeader.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startStr = spec.substring(0, dash).trim()
            val endStr = spec.substring(dash + 1).trim()
            val start: Long
            val end: Long
            if (startStr.isEmpty()) {
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                start = max(0L, total - suffix)
                end = total - 1L
            } else {
                start = startStr.toLongOrNull() ?: return null
                val rawEnd = if (endStr.isEmpty()) total - 1L else endStr.toLongOrNull() ?: return null
                end = min(rawEnd, total - 1L)
            }
            if (start < 0L || start >= total || start > end) return null
            return start to end
        }

        private fun parseDeflateStartOffset(rangeHeader: String?, total: Long): Long? {
            if (rangeHeader.isNullOrBlank()) return 0L
            val spec = rangeHeader.trim().removePrefix("bytes=").substringBefore(',').trim()
            val startStr = spec.substringBefore('-').trim()
            if (startStr.isEmpty()) return null
            val start = startStr.toLongOrNull() ?: return null
            if (start < 0L || (total > 0L && start >= total)) return null
            return start
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long) {
            var remaining = bytesToSkip
            val scratch = ByteArray(8192)
            while (remaining > 0L) {
                val n = stream.read(scratch, 0, min(scratch.size.toLong(), remaining).toInt())
                if (n < 0) throw EOFException("Unexpected EOF while skipping $remaining bytes")
                remaining -= n
            }
        }

        internal fun mimeTypeForVideoName(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "mkv" -> "video/x-matroska"
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "ts", "m2ts" -> "video/mp2t"
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            }
        }

        internal fun compareNatural(a: String, b: String): Int {
            val tokensA = NUMBER_TOKEN_REGEX.findAll(a).map { it.value }.toList()
            val tokensB = NUMBER_TOKEN_REGEX.findAll(b).map { it.value }.toList()
            val limit = min(tokensA.size, tokensB.size)
            for (i in 0 until limit) {
                val ta = tokensA[i]
                val tb = tokensB[i]
                val na = ta.toLongOrNull()
                val nb = tb.toLongOrNull()
                val cmp = if (na != null && nb != null) {
                    na.compareTo(nb)
                } else {
                    ta.compareTo(tb, ignoreCase = true)
                }
                if (cmp != 0) return cmp
            }
            return tokensA.size.compareTo(tokensB.size)
        }
    }
}
