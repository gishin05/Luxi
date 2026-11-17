package eu.kanade.tachiyomi.source.anime

import androidx.media3.common.MimeTypes
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.player.friendlyTrackMime
import eu.kanade.tachiyomi.ui.player.isHlsStream
import eu.kanade.tachiyomi.ui.player.isUnlabeledClosedCaption
import eu.kanade.tachiyomi.ui.player.looksLikeDash
import eu.kanade.tachiyomi.ui.player.looksLikeHls
import eu.kanade.tachiyomi.ui.player.mimeType
import eu.kanade.tachiyomi.ui.player.normalizeSubtitleLanguageTag
import eu.kanade.tachiyomi.ui.player.resolveSubtitleConfigLabel
import eu.kanade.tachiyomi.ui.player.resolveTrackLabel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import rx.Observable

class VideoPlaybackCompatTest {

    private open class FakeAnimePaheSource : AnimeHttpSource() {
        override val name: String = "AnimePahe"
        override val baseUrl: String = "https://animepahe.pw"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        var episodeListCalls: Int = 0

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
            episodeListCalls++
            throw AssertionError("getEpisodeList should not run when chapter resolves hosters directly")
        }

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
            return listOf(
                Hoster(
                    hosterUrl = "${episode.url}#kwik",
                    hosterName = "Kwik",
                    lazy = false,
                    videoList = listOf(
                        Video(
                            videoUrl = "",
                            videoTitle = "1080p (Sub)",
                            internalData = "mp4_pahe::https://pahe.win/test",
                            initialized = false,
                        ),
                    ),
                ),
            )
        }

        override suspend fun resolveVideo(video: Video): Video {
            return video.copy(
                videoUrl = "http://localhost:45678/mp4?url=https%3A%2F%2Fcdn.uwucdn.top%2Fstream.mp4",
                initialized = true,
            )
        }

        override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
            AnimesPage(emptyList(), false)
    }

    @Test
    fun `does not call getEpisodeList when episode resolves directly`() = runTest {
        val fakeSource = FakeAnimePaheSource()
        val adapter = AnimeSourceAdapter(fakeSource)

        val chapter = SChapter.create().apply {
            url = "/play/anime-session/ep-session"
            name = "Episode 1"
            chapter_number = 1f
        }

        val videos = adapter.getVideos(chapter)
        fakeSource.episodeListCalls shouldBe 0
        videos shouldHaveSize 1
        videos[0].videoTitle shouldBe "1080p (Sub)"
    }

    @Test
    fun `preserves localhost proxy urls while still unwrapping remote redirectors`() = runTest {
        val adapter = AnimeSourceAdapter(FakeAnimePaheSource())

        val mp4Proxy = Video(
            videoUrl = "http://localhost:45678/mp4?url=https%3A%2F%2Fcdn.uwucdn.top%2Fclip.mp4",
            videoTitle = "1080p (Sub)",
            initialized = true,
        )
        adapter.resolveVideo(mp4Proxy)?.videoUrl shouldBe mp4Proxy.videoUrl

        val hlsProxy = Video(
            videoUrl = "http://127.0.0.1:45678/m3u8?url=https%3A%2F%2Fcdn.uwucdn.top%2Fmaster",
            videoTitle = "720p (Sub)",
            initialized = true,
        )
        adapter.resolveVideo(hlsProxy)?.videoUrl shouldBe hlsProxy.videoUrl

        val remoteWrapper = Video(
            videoUrl = "https://video-seed.dev/?url=https://video-downloads.googleusercontent.com/abc",
            videoTitle = "Instant Download",
            initialized = true,
        )
        adapter.resolveVideo(remoteWrapper)?.videoUrl shouldBe
            "https://video-downloads.googleusercontent.com/abc"
    }

    @Test
    fun `propagates hoster failure instead of returning empty list`() = runTest {
        val failingSource = object : FakeAnimePaheSource() {
            override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()
            override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
                throw IllegalStateException("HTTP 403")
            }
        }
        val adapter = AnimeSourceAdapter(failingSource)
        val chapter = SChapter.create().apply {
            url = "/play/anime-session/ep-session"
            name = "Episode 1"
            chapter_number = 1f
        }

        val error = shouldThrow<IllegalStateException> {
            adapter.getVideos(chapter)
        }
        error.message shouldBe "HTTP 403"
    }

    @Test
    fun `AnimeCatalogueSource default hoster and video methods throw instead of recursing`() = runTest {
        val minimalSource = object : AnimeCatalogueSource {
            override val id: Long = 1L
            override val name: String = "Minimal"
            override val lang: String = "en"
            override val supportsLatest: Boolean = false

            @Suppress("OVERRIDE_DEPRECATION")
            override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = Observable.empty()

            @Suppress("OVERRIDE_DEPRECATION")
            override fun fetchSearchAnime(
                page: Int,
                query: String,
                filters: AnimeFilterList,
            ): Observable<AnimesPage> = Observable.empty()

            @Suppress("OVERRIDE_DEPRECATION")
            override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = Observable.empty()
        }

        shouldThrow<IllegalStateException> {
            minimalSource.getHosterList(SEpisode.create())
        }
        shouldThrow<IllegalStateException> {
            minimalSource.getVideoList(Hoster("", ""))
        }
    }

    @Test
    fun `classifies local mp4 proxy hls dash and query subtitles accurately`() {
        val paheMp4 = Video(
            videoUrl = "http://localhost:45678/mp4?url=https%3A%2F%2Fcdn.example%2Fvideo.mp4",
            videoTitle = "1080p (Sub)",
        )
        paheMp4.videoUrl.looksLikeHls() shouldBe false
        paheMp4.isHlsStream(forcedHls = null) shouldBe false

        val paheHls = Video(
            videoUrl = "http://localhost:45678/m3u8?url=https%3A%2F%2Fcdn.example%2Fplaylist",
            videoTitle = "720p (Sub)",
        )
        paheHls.videoUrl.looksLikeHls() shouldBe true
        paheHls.isHlsStream(forcedHls = null) shouldBe true

        val kaaDash = Video(
            videoUrl = "https://kaa.lt/manifest?id=123&type=dash",
            videoTitle = "BirdStream",
        )
        kaaDash.videoUrl.looksLikeDash() shouldBe true
        kaaDash.videoUrl.looksLikeHls() shouldBe false
        kaaDash.isHlsStream(forcedHls = null) shouldBe false

        Track("https://sub.wyzie.ru/sub.srt?token=abc", "en").mimeType() shouldBe MimeTypes.APPLICATION_SUBRIP
        Track("https://sub.wyzie.ru/sub.ass?token=abc", "en").mimeType() shouldBe MimeTypes.TEXT_SSA
    }

    @Test
    fun `filters phantom cea-608 tracks and resolves descriptive subtitle labels`() {
        isUnlabeledClosedCaption(MimeTypes.APPLICATION_CEA608, label = null, language = null) shouldBe true
        isUnlabeledClosedCaption(MimeTypes.APPLICATION_CEA608, label = "Default", language = "und") shouldBe true
        isUnlabeledClosedCaption(MimeTypes.APPLICATION_CEA608, label = null, language = "en") shouldBe false
        isUnlabeledClosedCaption(MimeTypes.TEXT_VTT, label = null, language = null) shouldBe false

        normalizeSubtitleLanguageTag("English - SDH") shouldBe null
        normalizeSubtitleLanguageTag("en") shouldBe "en"
        normalizeSubtitleLanguageTag("pt_BR") shouldBe "pt-BR"

        resolveSubtitleConfigLabel("English - SDH", 1) shouldBe "English - SDH"
        resolveSubtitleConfigLabel("en", 1) shouldBe "English"
        resolveSubtitleConfigLabel("und", 2) shouldBe "Subtitle 2"

        var count = 0
        resolveTrackLabel(label = "English (SDH)", language = null, isAudio = false) { ++count } shouldBe
            "English (SDH)"
        resolveTrackLabel(label = null, language = "Spanish (Latin America)", isAudio = false) { ++count } shouldBe
            "Spanish (Latin America)"
        resolveTrackLabel(label = null, language = "und", isAudio = false) { ++count } shouldBe "Subtitle 1"

        friendlyTrackMime(MimeTypes.APPLICATION_CEA608) shouldBe "CEA-608"
        friendlyTrackMime(MimeTypes.APPLICATION_SUBRIP) shouldBe "SRT"
        friendlyTrackMime(MimeTypes.TEXT_SSA) shouldBe "ASS/SSA"
        friendlyTrackMime(MimeTypes.TEXT_VTT) shouldBe "VTT"
    }
}
