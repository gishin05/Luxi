package eu.kanade.tachiyomi.ui

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.novel.NovelReaderActivity
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.model.MediaType
import tachiyomi.domain.source.service.SourceMediaTypeResolver

/**
 * Returns the activity that can display [chapterId], based on the media type of [source].
 */
fun chapterIntent(
    context: Context,
    source: Source?,
    mangaId: Long,
    chapterId: Long,
    chapterName: String,
    mangaTitle: String = "",
): Intent {
    return when (source?.let { SourceMediaTypeResolver.resolve(it) } ?: MediaType.COMIX) {
        MediaType.ANIME, MediaType.MOVIES -> PlayerActivity.newIntent(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterTitle = chapterName,
            mangaTitle = mangaTitle,
        )
        MediaType.NOVELS -> NovelReaderActivity.newIntent(
            context = context,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterTitle = chapterName,
            mangaTitle = mangaTitle,
        )
        MediaType.COMIX -> ReaderActivity.newIntent(context, mangaId, chapterId)
    }
}

fun launchChapter(
    context: Context,
    source: Source?,
    mangaId: Long,
    chapterId: Long,
    chapterName: String,
    mangaTitle: String = "",
) {
    context.startActivity(chapterIntent(context, source, mangaId, chapterId, chapterName, mangaTitle))
}

fun launchChapter(
    context: Context,
    source: Source?,
    chapter: Chapter,
    mangaTitle: String = "",
) {
    launchChapter(context, source, chapter.mangaId, chapter.id, chapter.name, mangaTitle)
}
