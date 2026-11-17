package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A source that provides readable text / novel chapters rather than comic image pages.
 */
interface NovelSource : CatalogueSource {

    /**
     * Fetches the text or HTML content for the specified [chapter].
     */
    suspend fun getChapterContent(chapter: SChapter): String

    /**
     * Default implementation of [getPageList] for text novel sources.
     * Yields a single descriptor page carrying the chapter URL.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return listOf(Page(0, chapter.url, ""))
    }
}
