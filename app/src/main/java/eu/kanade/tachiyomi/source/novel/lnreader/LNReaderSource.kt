package eu.kanade.tachiyomi.source.novel.lnreader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CoverHttpSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI
import kotlin.math.abs

class LNReaderSource(
    val plugin: LNPluginMeta,
    private val repository: LNReaderPluginRepository,
    private val engine: LNReaderEngine,
    private val networkHelper: NetworkHelper? = null,
) : NovelSource, CoverHttpSource {

    override val id: Long = ID_PREFIX + (abs(plugin.id.hashCode().toLong()) % 100_000_000L)

    override val name: String = "${plugin.name} (Novels)"

    override val lang: String = when (plugin.lang.lowercase()) {
        "english" -> "en"
        "spanish", "español" -> "es"
        "french", "français" -> "fr"
        "german", "deutsch" -> "de"
        "portuguese", "português" -> "pt"
        "russian", "русский" -> "ru"
        else -> plugin.lang.lowercase()
    }

    override val supportsLatest: Boolean = true

    override val coverHeaders: Headers?
        get() = networkHelper?.let {
            Headers.Builder()
                .add("User-Agent", it.defaultUserAgentProvider())
                .add("Referer", plugin.site)
                .build()
        }

    override val coverClient: OkHttpClient?
        get() = networkHelper?.client

    private suspend fun getScript(): String {
        return repository.getPluginScript(plugin)
    }

    private fun LNNovelItem.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.path
        title = this@toSManga.name
        thumbnail_url = normalizeCoverUrl(this@toSManga.cover, plugin.site)
        status = SManga.ONGOING
        initialized = false
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val script = getScript()
        val items = engine.getPopularNovels(plugin.id, script, page)
        return MangasPage(items.map { it.toSManga() }, items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val script = getScript()
        val items = engine.getLatestNovels(plugin.id, script, page)
        return MangasPage(items.map { it.toSManga() }, items.isNotEmpty())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val script = getScript()
        val items = engine.searchNovels(plugin.id, script, query, page)
        return MangasPage(items.map { it.toSManga() }, items.isNotEmpty())
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var latestManga = manga
        val collectedChapters = mutableListOf<SChapter>()
        getMangaUpdateProgressively(
            manga = manga,
            chapters = chapters,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            onDetails = { latestManga = it },
            onChaptersChunk = { chunk, _ -> collectedChapters.addAll(chunk) },
        )
        return SMangaUpdate(latestManga, if (fetchChapters) collectedChapters else chapters)
    }

    override suspend fun getMangaUpdateProgressively(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        onDetails: suspend (SManga) -> Unit,
        onChaptersChunk: suspend (chapters: List<SChapter>, isFinal: Boolean) -> Unit,
    ) {
        val script = getScript()
        var chapterOffset = 0
        engine.parseNovelProgressively(
            pluginId = plugin.id,
            script = script,
            path = manga.url,
            onDetails = { details ->
                if (!fetchDetails) return@parseNovelProgressively
                val updatedManga = manga.copy().apply {
                    title = details.name.ifBlank { manga.title }
                    val resolvedCover = normalizeCoverUrl(details.cover, plugin.site)
                    if (resolvedCover.isNotBlank()) thumbnail_url = resolvedCover
                    author = details.author ?: manga.author
                    artist = details.artist ?: details.author ?: manga.artist
                    status = when (details.status?.lowercase()) {
                        "completed" -> SManga.COMPLETED
                        "ongoing" -> SManga.ONGOING
                        "licensed" -> SManga.LICENSED
                        "publishing finished" -> SManga.PUBLISHING_FINISHED
                        "cancelled" -> SManga.CANCELLED
                        "on hiatus" -> SManga.ON_HIATUS
                        else -> SManga.UNKNOWN
                    }
                    genre = details.genres ?: manga.genre
                    description = details.summary ?: manga.description
                    initialized = true
                }
                onDetails(updatedManga)
            },
            onChapters = { chunk, isFinal ->
                if (!fetchChapters) return@parseNovelProgressively
                val startOffset = chapterOffset
                chapterOffset += chunk.size
                val now = System.currentTimeMillis()
                val mapped = chunk.mapIndexed { index, ch ->
                    val fallbackNum = startOffset + index + 1
                    SChapter.create().apply {
                        url = ch.path
                        name = ch.name.ifBlank { "Chapter $fallbackNum" }
                        chapter_number = (ch.chapterNumber ?: fallbackNum.toDouble()).toFloat()
                        date_upload = now
                    }
                }.let { list ->
                    if (startOffset == 0 && isFinal) list.reversed() else list
                }
                onChaptersChunk(mapped, isFinal)
            },
        )
    }

    override suspend fun getChapterContent(chapter: SChapter): String {
        val script = getScript()
        return engine.parseChapter(plugin.id, script, chapter.url)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return listOf(Page(0, chapter.url, ""))
    }

    companion object {
        const val ID_PREFIX = 810000000L

        internal fun normalizeCoverUrl(rawCover: String?, site: String): String {
            val trimmed = rawCover?.trim().orEmpty()
            if (trimmed.isBlank() || trimmed.contains("coverNotAvailable", ignoreCase = true)) {
                return ""
            }
            return when {
                trimmed.startsWith("data:", ignoreCase = true) -> trimmed
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> runCatching {
                    URI(site).resolve(trimmed).toString()
                }.getOrDefault(trimmed)
            }
        }
    }
}
