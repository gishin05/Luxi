package eu.kanade.tachiyomi.source.anime

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.animesource.UnmeteredSource as AnimeUnmeteredSource

class AnimeSourceAdapterTest {

    private open class FakeMoviesModSource : AnimeHttpSource() {
        override val name: String = "Movies Mod"
        override val baseUrl: String = "https://moviesmod.tw"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        var episodeListCalled = false

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
            episodeListCalled = true
            return listOf(
                SEpisode.create().apply {
                    url = """{"urls":[{"quality":"720p","url":"https://en.thenaukriadda.in/?sid=123"}]}"""
                    name = "Full Movie"
                    episode_number = 1f
                },
            )
        }

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
            if (!episode.url.startsWith("{")) {
                return emptyList()
            }
            return listOf(
                Hoster(
                    hosterName = "Instant Download",
                    videoList = listOf(
                        Video(
                            videoUrl = "https://video-seed.dev/?url=" +
                                "https://video-downloads.googleusercontent.com/stream123",
                            videoTitle = "Instant Download - 720p",
                        ),
                        Video(
                            videoUrl = "https://pub-dead.r2.dev/stream.mkv",
                            videoTitle = "Cloud Download - 720p",
                        ),
                    ),
                ),
            )
        }

        override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
            AnimesPage(emptyList(), false)
        override suspend fun getAnimeEpisodeUpdate(
            anime: SAnime,
            episodes: List<SEpisode>,
            fetchDetails: Boolean,
            fetchEpisodes: Boolean,
        ): SAnimeEpisodeUpdate {
            val eps = if (fetchEpisodes) getEpisodeList(anime) else episodes
            return SAnimeEpisodeUpdate(anime, eps)
        }
    }

    @Test
    fun `resolves videos when chapter url is raw path instead of json`() = runTest {
        val fakeSource = FakeMoviesModSource()
        val adapter = AnimeSourceAdapter(fakeSource)

        val rawChapter = SChapter.create().apply {
            url = "/download-allen-2026-movie/"
            name = "Movie"
            chapter_number = 1f
        }

        val videos = adapter.getVideos(rawChapter)
        fakeSource.episodeListCalled shouldBe true
        videos shouldHaveSize 2
        videos[0].videoUrl shouldBe
            "https://video-seed.dev/?url=https://video-downloads.googleusercontent.com/stream123"
        videos[1].videoUrl shouldBe "https://pub-dead.r2.dev/stream.mkv"
    }

    @Test
    fun `unwraps direct google stream in resolveVideo`() = runTest {
        val fakeSource = FakeMoviesModSource()
        val adapter = AnimeSourceAdapter(fakeSource)

        val seedVideo = Video(
            videoUrl = "https://video-seed.dev/?url=https://video-downloads.googleusercontent.com/stream123",
            videoTitle = "Instant Download - 720p",
        )

        val resolved = adapter.resolveVideo(seedVideo)
        resolved?.videoUrl shouldBe "https://video-downloads.googleusercontent.com/stream123"
    }

    @Test
    fun `provides fallback chapter when source episodes are empty`() = runTest {
        val emptySource = object : FakeMoviesModSource() {
            override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()
            override suspend fun getAnimeEpisodeUpdate(
                anime: SAnime,
                episodes: List<SEpisode>,
                fetchDetails: Boolean,
                fetchEpisodes: Boolean,
            ): SAnimeEpisodeUpdate = SAnimeEpisodeUpdate(anime, emptyList())
        }
        val adapter = AnimeSourceAdapter(emptySource)

        val manga = SManga.create().apply {
            url = "/movie/123"
            title = "Test Movie"
        }

        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        update.chapters shouldHaveSize 1
        update.chapters[0].name shouldBe "Test Movie"
        update.chapters[0].url shouldBe "/movie/123"
    }

    @Test
    fun `wraps ConfigurableAnimeSource and UnmeteredSource selectively via create`() {
        val plainSource = FakeMoviesModSource()
        val plainAdapter = AnimeSourceAdapter.create(plainSource)
        plainAdapter.shouldNotBeInstanceOf<ConfigurableSource>()
        plainAdapter.shouldNotBeInstanceOf<UnmeteredSource>()

        val fakePrefs = mockk<SharedPreferences>()
        val fakeScreen = mockk<PreferenceScreen>(relaxed = true)
        var setupCalledWith: PreferenceScreen? = null

        val configurableSource = object : FakeMoviesModSource(), ConfigurableAnimeSource {
            override fun getSourcePreferences(): SharedPreferences = fakePrefs
            override fun setupPreferenceScreen(screen: PreferenceScreen) {
                setupCalledWith = screen
            }
        }
        val configurableAdapter = AnimeSourceAdapter.create(configurableSource)
        configurableAdapter.shouldBeInstanceOf<ConfigurableSource>()
        configurableAdapter.shouldNotBeInstanceOf<UnmeteredSource>()
        (configurableAdapter as ConfigurableSource).getSourcePreferences() shouldBe fakePrefs
        configurableAdapter.setupPreferenceScreen(fakeScreen)
        setupCalledWith shouldBe fakeScreen

        val unmeteredConfigurableSource =
            object : FakeMoviesModSource(), ConfigurableAnimeSource, AnimeUnmeteredSource {
                override fun getSourcePreferences(): SharedPreferences = fakePrefs
                override fun setupPreferenceScreen(screen: PreferenceScreen) {
                    setupCalledWith = screen
                }
            }
        val bothAdapter = AnimeSourceAdapter.create(unmeteredConfigurableSource)
        bothAdapter.shouldBeInstanceOf<ConfigurableSource>()
        bothAdapter.shouldBeInstanceOf<UnmeteredSource>()

        val onlyUnmeteredSource = object : FakeMoviesModSource(), AnimeUnmeteredSource {}
        val unmeteredAdapter = AnimeSourceAdapter.create(onlyUnmeteredSource)
        unmeteredAdapter.shouldNotBeInstanceOf<ConfigurableSource>()
        unmeteredAdapter.shouldBeInstanceOf<UnmeteredSource>()
    }

    @Test
    fun `fetches season episodes when details specify FetchType Seasons`() = runTest {
        val seasonSource = object : AnimeHttpSource() {
            override val name: String = "Jellyfin"
            override val baseUrl: String = "https://jellyfin.example"
            override val lang: String = "all"
            override val supportsLatest: Boolean = true
            override val client: OkHttpClient = OkHttpClient()

            override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
            override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
                AnimesPage(emptyList(), false)

            override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.copy().apply {
                fetch_type = FetchType.Seasons
                initialized = true
            }

            override suspend fun getSeasonList(anime: SAnime): List<SAnime> = listOf(
                SAnime.create().apply {
                    url = "/season/1"
                    title = "Season 1"
                },
                SAnime.create().apply {
                    url = "/season/2"
                    title = "Season 2"
                },
            )

            override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = when (anime.url) {
                "/season/1" -> listOf(
                    SEpisode.create().apply {
                        url = "/ep/1"
                        name = "Ep 1"
                        episode_number = 1f
                    },
                )
                "/season/2" -> listOf(
                    SEpisode.create().apply {
                        url = "/ep/2"
                        name = "Ep 2"
                        episode_number = 2f
                    },
                )
                else -> emptyList()
            }
        }

        val adapter = AnimeSourceAdapter.create(seasonSource)
        val manga = SManga.create().apply {
            url = "/series/1"
            title = "Series"
        }

        val progressiveChapters = mutableListOf<SChapter>()
        adapter.getMangaUpdateProgressively(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
            onDetails = {},
            onChaptersChunk = { chunk, _ -> progressiveChapters += chunk },
        )
        progressiveChapters shouldHaveSize 2
        progressiveChapters[0].url shouldBe "/ep/1"
        progressiveChapters[0].scanlator shouldBe "Season 1"
        progressiveChapters[1].url shouldBe "/ep/2"
        progressiveChapters[1].scanlator shouldBe "Season 2"

        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        update.chapters shouldHaveSize 2
        update.chapters[0].scanlator shouldBe "Season 1"
        update.chapters[1].scanlator shouldBe "Season 2"
    }

    @Test
    fun `exposes home and manga urls and attaches source headers only to non-loopback videos`() = runTest {
        val headeredSource = object : FakeMoviesModSource() {
            override fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", "LuxiTestAgent")

            override fun getHomeUrl(): String = "https://moviesmod.example/home"

            override fun getAnimeUrl(anime: SAnime): String = "https://moviesmod.example${anime.url}"
        }
        val adapter = AnimeSourceAdapter.create(headeredSource)

        adapter.isHttpBacked shouldBe true
        adapter.getHomeUrl() shouldBe "https://moviesmod.example/home"
        adapter.getMangaUrl(
            SManga.create().apply {
                url = "/show/42"
                title = "Show"
            },
        ) shouldBe "https://moviesmod.example/show/42"

        val remoteVideo = Video(
            videoUrl = "https://cdn.example.com/stream.m3u8",
            videoTitle = "1080p",
            initialized = true,
        )
        val resolvedRemote = adapter.resolveVideo(remoteVideo)
        resolvedRemote?.headers?.get("User-Agent") shouldBe "LuxiTestAgent"

        val loopbackVideo = Video(
            videoUrl = "http://127.0.0.1:8080/proxy.m3u8",
            videoTitle = "Proxy",
            initialized = true,
        )
        val resolvedLoopback = adapter.resolveVideo(loopbackVideo)
        resolvedLoopback?.headers shouldBe null
    }

    @Test
    fun `parses and validates anime lib versions across 12 to 17`() {
        ExtensionLoader.parseAnimeLibVersion(14, "14.34") shouldBe 14.0
        ExtensionLoader.parseAnimeLibVersion("16.0", "16.59") shouldBe 16.0
        ExtensionLoader.parseAnimeLibVersion(null, "14.34.1") shouldBe 14.0
        ExtensionLoader.parseAnimeLibVersion(0, "15.2") shouldBe 15.0

        listOf(12.0, 13.0, 14.0, 15.0, 16.0, 17.0).forEach { lib ->
            ExtensionLoader.isSupportedAnimeLibVersion(lib) shouldBe true
        }
        ExtensionLoader.isSupportedAnimeLibVersion(11.0) shouldBe false
        ExtensionLoader.isSupportedAnimeLibVersion(18.0) shouldBe false
        ExtensionLoader.isSupportedAnimeLibVersion(null) shouldBe false
    }
}
