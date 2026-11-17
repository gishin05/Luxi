package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

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
}
