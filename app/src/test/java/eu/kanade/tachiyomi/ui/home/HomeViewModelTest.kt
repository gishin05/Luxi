package eu.kanade.tachiyomi.ui.home

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.anime.AnimeSourceAdapter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

class HomeViewModelTest {

    private class FastNovelSource(
        override val id: Long,
        override val name: String,
    ) : NovelSource {
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getChapterContent(chapter: SChapter): String = ""

        override suspend fun getPopularManga(page: Int): MangasPage {
            return MangasPage(
                listOf(
                    SManga.create().apply {
                        url = "/novel-1"
                        title = "Fast Novel"
                        thumbnail_url = "https://example.com/novel.jpg"
                    },
                ),
                hasNextPage = false,
            )
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
    }

    private class GatedComixSource(
        override val id: Long,
        override val name: String,
        private val gate: CompletableDeferred<Unit>?,
    ) : CatalogueSource {
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            gate?.await()
            return MangasPage(
                listOf(
                    SManga.create().apply {
                        url = "/manga-1"
                        title = "Manga Title"
                    },
                ),
                hasNextPage = false,
            )
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
    }

    private class GatedAnimeSource(
        override val id: Long,
        override val name: String,
        private val gate: CompletableDeferred<Unit>,
    ) : AnimeCatalogueSource {
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getPopularAnime(page: Int): AnimesPage {
            gate.await()
            return AnimesPage(
                listOf(
                    SAnime.create().apply {
                        url = "/anime-1"
                        title = "Slow Anime"
                    },
                ),
                hasNextPage = false,
            )
        }

        override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
            AnimesPage(emptyList(), false)

        @Deprecated("Deprecated in interface")
        override fun fetchPopularAnime(page: Int): rx.Observable<AnimesPage> = rx.Observable.empty()

        @Deprecated("Deprecated in interface")
        override fun fetchLatestUpdates(page: Int): rx.Observable<AnimesPage> = rx.Observable.empty()

        @Deprecated("Deprecated in interface")
        override fun fetchSearchAnime(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ): rx.Observable<AnimesPage> = rx.Observable.empty()

        override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime
        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()
    }

    @Test
    fun `publishes completed shelves immediately without waiting for slower sections`(): Unit = runBlocking {
        val slowAnimeGate = CompletableDeferred<Unit>()
        val slowMoviesGate = CompletableDeferred<Unit>()

        val fastComix = GatedComixSource(id = 1L, name = "Fast Manga", gate = null)
        val fastNovels = FastNovelSource(id = 2L, name = "Fast Novels")
        val slowAnime = AnimeSourceAdapter(GatedAnimeSource(id = 3L, name = "Gogo Anime", gate = slowAnimeGate))
        val slowMovie = AnimeSourceAdapter(GatedAnimeSource(id = 4L, name = "Slow Movie", gate = slowMoviesGate))

        val sourcesFlow = MutableStateFlow<List<Source>>(listOf(fastComix, fastNovels, slowAnime, slowMovie))
        val sourceManager = mockk<SourceManager> {
            every { sources } returns sourcesFlow
        }
        val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())
        val networkToLocalManga = mockk<NetworkToLocalManga>()
        coEvery { networkToLocalManga.invoke(any<List<Manga>>()) } answers {
            val input = firstArg<List<Manga>>()
            input.mapIndexed { idx, manga -> manga.copy(id = manga.source * 100L + idx) }
        }

        val viewModel = HomeViewModel(sourceManager, sourcePreferences, networkToLocalManga)

        // Comix and Novels should finish immediately while Anime and Movies are still blocked
        eventually(3.seconds) {
            val state = viewModel.state.value
            state.comixLoading shouldBe false
            state.novelsLoading shouldBe false
            state.comixList shouldHaveSize 1
            state.novelsList shouldHaveSize 1
            state.animeLoading shouldBe true
            state.moviesLoading shouldBe true
        }

        slowAnimeGate.complete(Unit)
        slowMoviesGate.complete(Unit)

        eventually(3.seconds) {
            val state = viewModel.state.value
            state.animeLoading shouldBe false
            state.moviesLoading shouldBe false
            state.animeList shouldHaveSize 1
            state.moviesList shouldHaveSize 1
            state.isLoading shouldBe false
        }
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        private val mainThread = newSingleThreadContext("HomeViewModelTestMain")

        @BeforeAll
        @JvmStatic
        fun setUp() {
            Dispatchers.setMain(mainThread)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            Dispatchers.resetMain()
            mainThread.close()
        }
    }
}
