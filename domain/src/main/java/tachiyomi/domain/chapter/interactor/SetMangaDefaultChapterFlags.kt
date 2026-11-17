package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga

@Inject
class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val getFavorites: GetFavorites,
) {

    suspend fun await(manga: Manga) {
        val defaultFlags = computeDefaultFlags()
        if (manga.chapterFlags == defaultFlags) return
        with(libraryPreferences) {
            setMangaChapterFlags.awaitSetAllFlags(
                mangaIds = listOf(manga.id),
                unreadFilter = filterChapterByRead.get(),
                downloadedFilter = filterChapterByDownloaded.get(),
                bookmarkedFilter = filterChapterByBookmarked.get(),
                sortingMode = sortChapterBySourceOrNumber.get(),
                sortingDirection = sortChapterByAscendingOrDescending.get(),
                displayMode = displayChapterByNameOrNumber.get(),
            )
        }
    }

    suspend fun awaitAll() {
        setDefaultFlags(getFavorites.await().map { it.id })
    }

    private fun computeDefaultFlags(): Long {
        with(libraryPreferences) {
            return 0L
                .setFlag(filterChapterByRead.get(), Manga.CHAPTER_UNREAD_MASK)
                .setFlag(filterChapterByDownloaded.get(), Manga.CHAPTER_DOWNLOADED_MASK)
                .setFlag(filterChapterByBookmarked.get(), Manga.CHAPTER_BOOKMARKED_MASK)
                .setFlag(sortChapterBySourceOrNumber.get(), Manga.CHAPTER_SORTING_MASK)
                .setFlag(sortChapterByAscendingOrDescending.get(), Manga.CHAPTER_SORT_DIR_MASK)
                .setFlag(displayChapterByNameOrNumber.get(), Manga.CHAPTER_DISPLAY_MASK)
        }
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }

    private suspend fun setDefaultFlags(mangaIds: List<Long>) {
        with(libraryPreferences) {
            setMangaChapterFlags.awaitSetAllFlags(
                mangaIds = mangaIds,
                unreadFilter = filterChapterByRead.get(),
                downloadedFilter = filterChapterByDownloaded.get(),
                bookmarkedFilter = filterChapterByBookmarked.get(),
                sortingMode = sortChapterBySourceOrNumber.get(),
                sortingDirection = sortChapterByAscendingOrDescending.get(),
                displayMode = displayChapterByNameOrNumber.get(),
            )
        }
    }
}
