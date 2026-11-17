package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import fi.iki.elonen.NanoHTTPD
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MoviesModCompatTest {

    private val runningServers = mutableListOf<NanoHTTPD>()

    @AfterEach
    fun tearDown() {
        runningServers.forEach { runCatching { it.stop() } }
        runningServers.clear()
    }

    private open class FakeMoviesModSource(
        override val baseUrl: String,
        override val name: String = "Movies Mod",
        override val client: OkHttpClient = OkHttpClient(),
        private val onGetEpisodeList: suspend (SAnime) -> List<SEpisode> = {
            throw Exception("Only Zip Pack Available")
        },
        private val onGetHosterList: suspend (SEpisode) -> List<Hoster> = { emptyList() },
    ) : AnimeHttpSource() {
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = onGetEpisodeList(anime)

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> = onGetHosterList(episode)

        override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
            AnimesPage(emptyList(), false)
    }

    private fun startOriginServer(
        handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response,
    ): String {
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun useGzipWhenAccepted(r: Response): Boolean = false

            override fun serve(session: IHTTPSession): Response = handler(session)
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        runningServers += server
        return "http://127.0.0.1:${server.listeningPort}"
    }

    private fun serveRangeBytes(
        session: NanoHTTPD.IHTTPSession,
        bytes: ByteArray,
        mime: String = "application/zip",
    ): NanoHTTPD.Response {
        val range = session.headers["range"]
        if (session.method == NanoHTTPD.Method.HEAD) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mime,
                ByteArrayInputStream(ByteArray(0)),
                0L,
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", bytes.size.toString())
            }
        }
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val start = spec.substringBefore('-').toIntOrNull() ?: 0
            val rawEnd = spec.substringAfter('-', "").toIntOrNull() ?: (bytes.size - 1)
            val end = rawEnd.coerceAtMost(bytes.size - 1)
            val slice = bytes.copyOfRange(start, end + 1)
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                mime,
                ByteArrayInputStream(slice),
                slice.size.toLong(),
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            }
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).apply {
            addHeader("Accept-Ranges", "bytes")
        }
    }

    @Test
    fun `recovers episodes via retry when MoviesMod throws Only Zip Pack Available on 429 burst`() = runTest {
        var archiveHits = 0
        lateinit var originBase: String
        originBase = startOriginServer { session ->
            when (session.uri) {
                "/movie-post/" -> NanoHTTPD.newFixedLengthResponse(
                    """
                    <html><body>
                      <h3>Sample Movie (2026) 720p [900MB]</h3>
                      <p><a class="maxbutton-download-links" href="$originBase/archive/movie-720p">Download Links</a></p>
                    </body></html>
                    """.trimIndent(),
                )
                "/archive/movie-720p" -> {
                    archiveHits++
                    if (archiveHits == 1) {
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.TOO_MANY_REQUESTS,
                            "text/html",
                            "<html><body>Too Many Requests</body></html>",
                        ).apply { addHeader("Retry-After", "0") }
                    } else {
                        NanoHTTPD.newFixedLengthResponse(
                            """
                            <html><body>
                              <div class="timed-content-client_show_0_5_0">
                                <a href="$originBase/?sid=abc123">Fast Server (G-Drive)</a>
                              </div>
                            </body></html>
                            """.trimIndent(),
                        )
                    }
                }
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }

        val adapter = AnimeSourceAdapter(
            FakeMoviesModSource(baseUrl = originBase),
        )
        val manga = SManga.create().apply {
            url = "/movie-post/"
            title = "Sample Movie"
        }

        var emittedChapters: List<SChapter> = emptyList()
        adapter.getMangaUpdateProgressively(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
            onDetails = {},
            onChaptersChunk = { chapters, _ -> emittedChapters = chapters },
        )

        archiveHits shouldBe 2
        emittedChapters shouldHaveSize 1
        emittedChapters[0].url shouldContain "$originBase/?sid=abc123"
    }

    @Test
    fun `unpacks Batch Zip series into individual streamable episodes across sid and driveseed`() = runTest {
        val ep1Payload = ByteArray(16_384) { (it * 7 + 3).toByte() }
        val ep2Payload = ByteArray(12_288) { (it * 11 + 5).toByte() }
        val zipBytes = buildZipArchive(
            stored = false,
            entries = listOf(
                "Show/Show.S01E01.720p.mkv" to ep1Payload,
                "Show/Show.S01E02.720p.mkv" to ep2Payload,
            ),
        )

        lateinit var origin: String
        origin = startOriginServer { session ->
            when (session.uri) {
                "/show-post/" -> NanoHTTPD.newFixedLengthResponse(
                    """
                    <html><body>
                      <h3>Season 1 {English} 720p [400MB]</h3>
                      <p><a class="maxbutton-batch-zip" href="$origin/archives/batch-s1">Batch/Zip File</a></p>
                    </body></html>
                    """.trimIndent(),
                )
                "/archives/batch-s1" -> {
                    val encodedSid = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("$origin/archives/batch-s1".toByteArray())
                    NanoHTTPD.newFixedLengthResponse(
                        """
                        <html><body>
                          <div class="timed-content-client_show_0_5_0">
                            <a href="$origin/?sid=$encodedSid">Fast Server 1 (G-Drive)</a>
                          </div>
                        </body></html>
                        """.trimIndent(),
                    )
                }
                "/" -> when {
                    session.parms.containsKey("sid") -> NanoHTTPD.newFixedLengthResponse(
                        """
                        <html><body>
                          <form id="landing" action="$origin/hop1" method="post">
                            <input type="hidden" name="_lp_http" value="tok1"/>
                          </form>
                        </body></html>
                        """.trimIndent(),
                    )
                    session.parms["lp_go"] == "lp-999" -> NanoHTTPD.newFixedLengthResponse(
                        """<html><body><script>window.location.replace("$origin/r?key=k1");</script></body></html>""",
                    )
                    else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
                }
                "/hop1" -> NanoHTTPD.newFixedLengthResponse(
                    """
                    <html><body>
                      <script>
                        var u = "$origin/?lp_go=lp-999";
                        setCookie('lp-999', 'cookieVal', 1);
                      </script>
                    </body></html>
                    """.trimIndent(),
                )
                "/r" -> NanoHTTPD.newFixedLengthResponse(
                    """<html><body><script>window.location.replace("/file/batchid");</script></body></html>""",
                )
                "/file/batchid" -> NanoHTTPD.newFixedLengthResponse(
                    """
                    <html><body>
                      <div class="card-body">
                        <ul class="list-group">
                          <li class="list-group-item">Name : Show.S01.720p.zip</li>
                          <li class="list-group-item">Format : zip</li>
                        </ul>
                        <a class="btn btn-outline-success" href="/wfile/batchid">Direct Links</a>
                      </div>
                    </body></html>
                    """.trimIndent(),
                )
                "/wfile/batchid" -> NanoHTTPD.newFixedLengthResponse(
                    """
                    <html><body>
                      <a href="$origin/wfile/batchid?type=1">Server 1</a>
                      <div class="mb-4">
                        <a href="$origin/worker-pack.zip">Download</a>
                      </div>
                    </body></html>
                    """.trimIndent(),
                )
                "/worker-pack.zip" -> serveRangeBytes(session, zipBytes)
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }

        val client = OkHttpClient()
        val adapter = AnimeSourceAdapter(FakeMoviesModSource(baseUrl = origin, client = client))
        val manga = SManga.create().apply {
            url = "/show-post/"
            title = "Sample Show"
        }

        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
        update.chapters shouldHaveSize 2

        val ep1Chapter = update.chapters.first { it.chapter_number == 1f }
        val videos = adapter.getVideos(ep1Chapter)
        videos shouldHaveSize 1
        val resolved = adapter.resolveVideo(videos[0])!!
        resolved.videoUrl shouldStartWith "http://127.0.0.1:"

        val streamedBytes = client.newCall(Request.Builder().url(resolved.videoUrl).get().build())
            .execute()
            .use { it.body.bytes() }
        streamedBytes shouldBe ep1Payload
    }

    @Test
    fun `streams STORED zip entries with 206 byte range seeking and DEFLATE zip entries cleanly`() {
        val storedPayload = ByteArray(1024) { (it % 251).toByte() }
        val storedZip = buildZipArchive(
            stored = true,
            entries = listOf("Movie.2026.mp4" to storedPayload),
        )
        val deflatePayload = ByteArray(8192) { ((it * 13) % 241).toByte() }
        val deflateZip = buildZipArchive(
            stored = false,
            entries = listOf("Episode.S01E03.mkv" to deflatePayload),
        )

        val origin = startOriginServer { session ->
            when (session.uri) {
                "/stored.zip" -> serveRangeBytes(session, storedZip)
                "/deflate.zip" -> serveRangeBytes(session, deflateZip)
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }

        val client = OkHttpClient()
        val headers = okhttp3.Headers.headersOf("User-Agent", "TestAgent")
        val proxy = RemoteZipStreamProxy().also { runningServers += it }

        val storedInspection = RemoteZipStreamProxy.inspectRemoteZip(client, headers, listOf("$origin/stored.zip"))
        storedInspection.entries shouldHaveSize 1
        val storedProxyUrl = proxy.registerEntry(
            listOf("$origin/stored.zip"),
            headers,
            client,
            storedInspection.entries[0],
        )

        val sliceResponse = client.newCall(
            Request.Builder().url(storedProxyUrl).header("Range", "bytes=120-279").get().build(),
        ).execute()
        sliceResponse.use { resp ->
            resp.code shouldBe 206
            resp.header("Content-Range") shouldBe "bytes 120-279/${storedPayload.size}"
            resp.body.bytes() shouldBe storedPayload.copyOfRange(120, 280)
        }

        val deflateInspection = RemoteZipStreamProxy.inspectRemoteZip(client, headers, listOf("$origin/deflate.zip"))
        deflateInspection.entries shouldHaveSize 1
        val deflateProxyUrl = proxy.registerEntry(
            listOf("$origin/deflate.zip"),
            headers,
            client,
            deflateInspection.entries[0],
        )

        val fullDeflate = client.newCall(Request.Builder().url(deflateProxyUrl).get().build()).execute()
        fullDeflate.use { resp ->
            resp.code shouldBe 200
            resp.header("Accept-Ranges") shouldBe "none"
            resp.body.bytes() shouldBe deflatePayload
        }
    }

    @Test
    fun `expands direct zip Video returned by MoviesMod during getVideos`() = runTest {
        val epBytes = ByteArray(2048) { (it * 3).toByte() }
        val zipBytes = buildZipArchive(
            stored = true,
            entries = listOf(
                "Pack/Show.S01E01.mkv" to epBytes,
                "Pack/Show.S01E02.mkv" to epBytes,
            ),
        )
        val origin = startOriginServer { session ->
            if (session.uri == "/pack.zip") {
                serveRangeBytes(session, zipBytes)
            } else {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }

        val client = OkHttpClient()
        val adapter = AnimeSourceAdapter(
            FakeMoviesModSource(
                baseUrl = origin,
                client = client,
                onGetEpisodeList = { emptyList() },
                onGetHosterList = {
                    listOf(
                        Hoster(
                            hosterUrl = "$origin/hoster",
                            hosterName = "Direct",
                            lazy = false,
                            videoList = listOf(
                                Video(
                                    videoUrl = "$origin/pack.zip",
                                    videoTitle = "720p Direct Zip",
                                    initialized = true,
                                ),
                            ),
                        ),
                    )
                },
            ),
        )

        val chapter = SChapter.create().apply {
            url = """{"urls":[{"quality":"720p","url":"$origin/s1"}]}"""
            name = "Batch Pack"
            chapter_number = 1f
        }

        val videos = adapter.getVideos(chapter)
        videos shouldHaveSize 2
        val resolved = adapter.resolveVideo(videos[1])!!
        resolved.videoUrl shouldStartWith "http://127.0.0.1:"
        val downloaded = client.newCall(Request.Builder().url(resolved.videoUrl).get().build())
            .execute()
            .use { it.body.bytes() }
        downloaded shouldBe epBytes
    }

    @Test
    fun `unwraps video-seed redirect url cleanly`() {
        val googleUrl = "https://video-downloads.googleusercontent.com/abc123?itag=22"
        val wrapper = "https://video-seed.dev/?url=$googleUrl"
        MoviesModCompat.unwrapInstantRedirectUrl(wrapper) shouldBe googleUrl
    }

    @Test
    fun `does not intercept non-MoviesMod episode errors`() = runTest {
        val failingOtherSource = object : AnimeHttpSource() {
            override val name: String = "OtherAnime"
            override val baseUrl: String = "https://example.com"
            override val lang: String = "en"
            override val supportsLatest: Boolean = false
            override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
                throw IllegalStateException("Original error")
            override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
            override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
                AnimesPage(emptyList(), false)
        }
        val adapter = AnimeSourceAdapter(failingOtherSource)
        val err = shouldThrow<IllegalStateException> {
            adapter.getMangaUpdateProgressively(
                manga = SManga.create().apply {
                    url = "/x"
                    title = "X"
                },
                chapters = emptyList(),
                fetchDetails = false,
                fetchChapters = true,
                onDetails = {},
                onChaptersChunk = { _, _ -> },
            )
        }
        err.message shouldBe "Original error"
    }

    private fun buildZipArchive(
        stored: Boolean,
        entries: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, payload) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = payload.size.toLong()
                    entry.compressedSize = payload.size.toLong()
                    entry.crc = CRC32().apply { update(payload) }.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(entry)
                zos.write(payload)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
