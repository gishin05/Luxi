package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import rx.Observable
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class SyncChaptersWithSourceTest {

    @Test
    fun `commits large chapter sync in multiple batches so initial chapters arrive first`(): Unit = runBlocking {
        val committedBatches = mutableListOf<List<Chapter>>()
        val chapterRepo = mockk<ChapterRepository>()
        coEvery { chapterRepo.getChapterByMangaId(any()) } returns emptyList()
        coEvery {
            chapterRepo.updateFromRemote(any(), any(), any())
        } answers {
            val added = secondArg<List<Chapter>>()
            committedBatches.add(added)
            added.mapIndexed { idx, ch -> ch.copy(id = (committedBatches.size * 1000 + idx).toLong()) }
        }

        val updateManga = mockk<UpdateManga>()
        coEvery { updateManga.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), any()) } returns true

        val excludedScanlators = mockk<GetExcludedScanlators>()
        coEvery { excludedScanlators.await(any()) } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            chapterRepository = chapterRepo,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getExcludedScanlators = excludedScanlators,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        )

        val rawChapters = (1..120).map { i ->
            SChapter.create().apply {
                url = "/ch-$i"
                name = "Chapter $i"
                chapter_number = i.toFloat()
            }
        }
        val source = mockk<CatalogueSource>()
        every { source.id } returns 1L

        val stored = sync.await(
            rawSourceChapters = rawChapters,
            manga = Manga.create().copy(id = 10L, source = 1L, title = "Test Novel"),
            source = source,
        )

        committedBatches.size shouldBeGreaterThan 1
        committedBatches.first() shouldHaveSize 50
        stored shouldHaveSize 120
    }

    @Test
    fun `progressive session commits early chunks and removes stale on final`(): Unit = runBlocking {
        val removedBatches = mutableListOf<List<Long>>()
        val addedBatches = mutableListOf<List<Chapter>>()
        val chapterRepo = mockk<ChapterRepository>()
        coEvery { chapterRepo.getChapterByMangaId(any()) } returns listOf(
            Chapter.create().copy(id = 999L, mangaId = 10L, url = "/stale", name = "Stale"),
        )
        coEvery { chapterRepo.updateFromRemote(any(), any(), any()) } answers {
            removedBatches.add(firstArg())
            val added = secondArg<List<Chapter>>()
            addedBatches.add(added)
            added.mapIndexed { idx, ch -> ch.copy(id = idx.toLong() + 1L) }
        }
        val updateManga = mockk<UpdateManga>()
        coEvery { updateManga.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), any()) } returns true
        val excludedScanlators = mockk<GetExcludedScanlators>()
        coEvery { excludedScanlators.await(any()) } returns emptySet()

        val sync = SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            chapterRepository = chapterRepo,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getExcludedScanlators = excludedScanlators,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        )
        val source = mockk<CatalogueSource>()
        every { source.id } returns 1L
        val session = sync.startSession(
            manga = Manga.create().copy(id = 10L, source = 1L, title = "Test Novel"),
            source = source,
        )

        session.addChunk(
            listOf(
                SChapter.create().apply {
                    url = "/ch-1"
                    name = "Chapter 1"
                },
            ),
            isFinal = false,
        )
        addedBatches shouldHaveSize 1
        removedBatches.single() shouldHaveSize 0

        val result = session.addChunk(
            listOf(
                SChapter.create().apply {
                    url = "/ch-2"
                    name = "Chapter 2"
                },
            ),
            isFinal = true,
        )
        result.orEmpty() shouldHaveSize 2
        removedBatches.last() shouldBe listOf(999L)
    }

    @Test
    fun `catalogue source emits details without waiting for slow chapter list`(): Unit = runBlocking {
        val chapterGate = CompletableDeferred<Unit>()
        val source = object : CatalogueSource {
            override val id: Long = 10L
            override val name: String = "Test Source"
            override val lang: String = "en"
            override val supportsLatest: Boolean = false

            override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
                MangasPage(emptyList(), false)

            @Suppress("DEPRECATION")
            override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
                return Observable.just(
                    SManga.create().apply {
                        url = manga.url
                        title = "Detailed Title"
                        description = "Updated description"
                        thumbnail_url = "https://example.com/cover.jpg"
                        initialized = true
                    },
                )
            }

            @Suppress("DEPRECATION")
            override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
                return Observable.fromCallable {
                    runBlocking { chapterGate.await() }
                    listOf(
                        SChapter.create().apply {
                            url = "/ch-1"
                            name = "Chapter 1"
                        },
                    )
                }
            }

            @Suppress("DEPRECATION")
            override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(emptyList())
        }

        val receivedDetails = CopyOnWriteArrayList<SManga>()
        val receivedChapters = CopyOnWriteArrayList<List<SChapter>>()

        val job = launch(Dispatchers.Default) {
            source.getMangaUpdateProgressively(
                manga = SManga.create().apply { url = "/entry" },
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = true,
                onDetails = { receivedDetails.add(it) },
                onChaptersChunk = { chunk, _ -> receivedChapters.add(chunk) },
            )
        }

        eventually(2.seconds) {
            receivedDetails shouldHaveSize 1
            receivedDetails.first().thumbnail_url shouldBe "https://example.com/cover.jpg"
            receivedChapters shouldHaveSize 0
        }

        chapterGate.complete(Unit)
        job.join()

        receivedChapters shouldHaveSize 1
        receivedChapters.first() shouldHaveSize 1
    }
}
