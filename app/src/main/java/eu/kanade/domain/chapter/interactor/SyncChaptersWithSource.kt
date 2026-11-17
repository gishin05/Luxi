package eu.kanade.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.util.TreeSet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Inject
class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: ClosedRange<Instant>? = null,
    ): List<Chapter> {
        val session = startSession(manga, source, manualFetch, fetchWindow)
        return session.addChunk(rawSourceChapters, isFinal = true).orEmpty()
    }

    internal suspend fun startSession(
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: ClosedRange<Instant>? = null,
    ): ProgressiveSession {
        val dbChapters = chapterRepository.getChapterByMangaId(manga.id)
        return ProgressiveSession(
            manga = manga,
            source = source,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
            dbChapters = dbChapters,
        )
    }

    internal inner class ProgressiveSession(
        private val manga: Manga,
        private val source: Source,
        private val manualFetch: Boolean,
        private val fetchWindow: ClosedRange<Instant>?,
        private val dbChapters: List<Chapter>,
    ) {
        private val timeZone = TimeZone.currentSystemDefault()
        private val now = Clock.System.now().toLocalDateTime(timeZone)
        private val nowInstant = now.toInstant(timeZone)
        private val dbChaptersByUrl = dbChapters.associateByTo(mutableMapOf()) { it.url }
        private val seenSourceUrls = mutableSetOf<String>()
        private val allAdded = mutableListOf<Chapter>()
        private val changedOrDuplicateReadUrls = mutableSetOf<String>()
        private var nextSourceOrder = 0L
        private var maxSeenUploadDate: Instant? = null
        private var committedAnyChunk = false

        suspend fun addChunk(
            rawSourceChapters: List<SChapter>,
            isFinal: Boolean,
            reconcileFinal: Boolean = false,
        ): List<Chapter>? {
            if (!isFinal && rawSourceChapters.isEmpty()) return null

            val isCanonicalReconcile = isFinal && reconcileFinal && committedAnyChunk
            if (isCanonicalReconcile) {
                // Intermediate chunks use best-effort order; reset so the canonical list assigns final sourceOrder and removes deduplicated entries.
                seenSourceUrls.clear()
                nextSourceOrder = 0L
            }

            val sManga = if (source is HttpSource) manga.toSManga() else null
            val freshChapters = rawSourceChapters
                .filter { seenSourceUrls.add(it.url) }
                .map { sChapter ->
                    val sanitized = Chapter.create()
                        .copyFromSChapter(sChapter)
                        .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                        .copy(mangaId = manga.id, sourceOrder = nextSourceOrder++)
                    if (sManga != null) {
                        val preparedSChapter = sanitized.toSChapter()
                        @Suppress("DEPRECATION")
                        (source as HttpSource).prepareNewChapter(preparedSChapter, sManga)
                        sanitized.copyFromSChapter(preparedSChapter)
                    } else {
                        sanitized
                    }
                }

            val recognizedChapters = if (freshChapters.isEmpty()) {
                emptyList()
            } else {
                freshChapters.zip(ChapterRecognition.parseChapterNumbers(manga.title, freshChapters)) { ch, numbering ->
                    ch.copy(chapterNumber = numbering.chapter?.let(ChapterRecognition::toDouble) ?: -1.0)
                }
            }

            val newChapters = mutableListOf<Chapter>()
            val updatedChapters = mutableListOf<ChapterRemoteUpdate>()

            for (chapter in recognizedChapters) {
                val dbChapter = dbChaptersByUrl[chapter.url]
                if (dbChapter == null) {
                    val toAddChapter = if (chapter.dateUpload == null) {
                        chapter.copy(dateUpload = maxSeenUploadDate ?: nowInstant)
                    } else {
                        maxSeenUploadDate = listOfNotNull(maxSeenUploadDate, chapter.dateUpload).maxOrNull()
                        chapter
                    }
                    newChapters.add(toAddChapter)
                } else if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            manga.title,
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        downloadManager.renameChapter(source, manga, dbChapter, chapter)
                    }

                    updatedChapters.add(
                        ChapterRemoteUpdate(
                            id = dbChapter.id,
                            name = chapter.name,
                            chapterNumber = chapter.chapterNumber,
                            scanlator = chapter.scanlator,
                            sourceOrder = chapter.sourceOrder,
                            dateUpload = chapter.dateUpload,
                            memo = chapter.memo,
                        ),
                    )
                }
            }

            if (isFinal && seenSourceUrls.isEmpty() && !source.isLocal()) {
                throw NoChaptersException()
            }

            val removedChapters = if (isFinal) {
                dbChaptersByUrl.values.filterNot { it.url in seenSourceUrls }.also { removed ->
                    removed.mapNotNull { downloadManager.getQueuedDownloadOrNull(it.id) }
                        .takeIf { it.isNotEmpty() }
                        ?.let { downloadManager.cancelQueuedDownloads(it) }
                }
            } else {
                emptyList()
            }

            val readChapterNumbers = dbChapters
                .asSequence()
                .filter { it.read && it.isRecognizedNumber }
                .map { it.chapterNumber }
                .toSet()

            val deletedChapterNumbers = TreeSet<Double>()
            val deletedReadChapterNumbers = TreeSet<Double>()
            val deletedBookmarkedChapterNumbers = TreeSet<Double>()
            removedChapters.forEach { chapter ->
                if (chapter.read) deletedReadChapterNumbers.add(chapter.chapterNumber)
                if (chapter.bookmark) deletedBookmarkedChapterNumbers.add(chapter.chapterNumber)
                deletedChapterNumbers.add(chapter.chapterNumber)
            }

            val deletedChapterNumberDateFetchMap = removedChapters
                .sortedByDescending { it.dateFetch }
                .associate { it.chapterNumber to it.dateFetch }

            val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
                .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

            // Compute fetch timestamps against full source order before splitting DB writes into batches
            var itemCount = newChapters.size
            val toAdd = newChapters.map { toAddItem ->
                var chapter = toAddItem.copy(dateFetch = nowInstant + (itemCount--).milliseconds)

                if (chapter.isRecognizedNumber) {
                    if ((chapter.chapterNumber in readChapterNumbers) && markDuplicateAsRead) {
                        changedOrDuplicateReadUrls.add(chapter.url)
                        chapter = chapter.copy(read = true)
                    }
                    if (chapter.chapterNumber in deletedChapterNumbers) {
                        chapter = chapter.copy(
                            read = chapter.chapterNumber in deletedReadChapterNumbers,
                            bookmark = chapter.chapterNumber in deletedBookmarkedChapterNumbers,
                        )
                        deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                            chapter = chapter.copy(dateFetch = it)
                        }
                        changedOrDuplicateReadUrls.add(chapter.url)
                    }
                }
                chapter
            }

            if (toAdd.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty()) {
                return if (isFinal) finalizeNoChanges(deletedChapterNumbers) else null
            }

            val storedForChunk = commitInChunks(
                toAdd = toAdd,
                removedIds = removedChapters.map { it.id },
                updatedChapters = updatedChapters,
            )
            storedForChunk.forEach { dbChaptersByUrl[it.url] = it }
            if (isCanonicalReconcile && removedChapters.isNotEmpty()) {
                val removedUrls = removedChapters.mapTo(HashSet()) { it.url }
                removedUrls.forEach { dbChaptersByUrl.remove(it) }
                allAdded.removeAll { it.url in removedUrls }
            }
            if (storedForChunk.isNotEmpty() || updatedChapters.isNotEmpty() || removedChapters.isNotEmpty()) {
                committedAnyChunk = true
            }
            allAdded.addAll(storedForChunk)

            if (!isFinal) {
                return null
            }

            val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()
            val foundRelease = dbChapters.isNotEmpty() &&
                allAdded.any { chapter ->
                    chapter.scanlator !in excludedScanlators &&
                        !(chapter.isRecognizedNumber && chapter.chapterNumber in deletedChapterNumbers)
                }

            updateManga.awaitUpdateFetchIntervalAndLastUpdate(manga, timeZone, now, fetchWindow, foundRelease)
            return allAdded.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
        }

        private suspend fun commitInChunks(
            toAdd: List<Chapter>,
            removedIds: List<Long>,
            updatedChapters: List<ChapterRemoteUpdate>,
        ): List<Chapter> {
            if (toAdd.size <= INITIAL_CHAPTER_CHUNK_SIZE) {
                return chapterRepository.updateFromRemote(
                    removedIds = removedIds,
                    added = toAdd,
                    updated = updatedChapters,
                )
            }

            // Insert visible top chapters first under the entry's current sort so the list fills
            // from the top immediately without jumping when later batches commit.
            val displaySort = getChapterSort(manga)
            val orderedToAdd = toAdd.sortedWith(displaySort)
            val firstBatch = orderedToAdd.take(INITIAL_CHAPTER_CHUNK_SIZE)
            val remainingBatches = orderedToAdd.drop(INITIAL_CHAPTER_CHUNK_SIZE).chunked(SUBSEQUENT_CHAPTER_CHUNK_SIZE)

            val stored = mutableListOf<Chapter>()
            stored.addAll(
                chapterRepository.updateFromRemote(
                    removedIds = emptyList(),
                    added = firstBatch,
                    updated = emptyList(),
                ),
            )

            remainingBatches.forEachIndexed { idx, batch ->
                yield()
                val isLast = idx == remainingBatches.lastIndex
                stored.addAll(
                    chapterRepository.updateFromRemote(
                        removedIds = if (isLast) removedIds else emptyList(),
                        added = batch,
                        updated = if (isLast) updatedChapters else emptyList(),
                    ),
                )
            }
            return stored
        }

        private suspend fun finalizeNoChanges(deletedChapterNumbers: TreeSet<Double>): List<Chapter> {
            val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()
            if (committedAnyChunk) {
                val foundRelease = dbChapters.isNotEmpty() &&
                    allAdded.any { chapter ->
                        chapter.scanlator !in excludedScanlators &&
                            !(chapter.isRecognizedNumber && chapter.chapterNumber in deletedChapterNumbers)
                    }
                updateManga.awaitUpdateFetchIntervalAndLastUpdate(manga, timeZone, now, fetchWindow, foundRelease)
                return allAdded.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
            }

            val isNextUpdateBeforeWindow = fetchWindow != null &&
                manga.nextUpdate.let { it == null || it < fetchWindow.start }
            if (manualFetch || manga.fetchInterval == 0 || isNextUpdateBeforeWindow) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    timeZone,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }
    }

    private companion object {
        const val INITIAL_CHAPTER_CHUNK_SIZE = 50
        const val SUBSEQUENT_CHAPTER_CHUNK_SIZE = 1000
    }
}
