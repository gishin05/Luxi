package mihon.domain.source.interactor

import android.content.Context
import android.content.SharedPreferences
import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ComixProgressBridge
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
class UpdateMangaFromRemote(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val coverCache: CoverCache,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
) {
    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: ClosedRange<Instant>? = null,
        onFirstBatchLoaded: (hasMore: Boolean) -> Unit = {},
    ): Result<RemoteMangaUpdate> {
        val source = sourceManager.getOrStub(manga.source)
        return invoke(
            source = source,
            manga = manga,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
            onFirstBatchLoaded = onFirstBatchLoaded,
        )
    }

    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: ClosedRange<Instant>? = null,
        onFirstBatchLoaded: (hasMore: Boolean) -> Unit = {},
    ): Result<RemoteMangaUpdate> = withContext(Dispatchers.IO) {
        try {
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
                .sortedBy { it.sourceOrder }
            val isComix = !source.isLocal() && ComixRequestGate.isComix(source)
            val knownChapters = if (isComix) {
                if (!manualFetch && !isComixIncompleteHistory(manga, chapters)) {
                    chapters.map(Chapter::toSChapter)
                } else {
                    emptyList()
                }
            } else {
                chapters.map(Chapter::toSChapter)
            }

            val listenComixProgress = !source.isLocal() &&
                (fetchDetails || fetchChapters) &&
                (isComix || manga.url.contains("/title/", ignoreCase = true))

            suspend fun runOnce(): List<Chapter> {
                val callbackMutex = Mutex()
                var currentManga = manga
                var syncSession: SyncChaptersWithSource.ProgressiveSession? = null
                var newChapters: List<Chapter> = emptyList()
                val finalized = AtomicBoolean(false)
                val receivedProgressChunks = AtomicBoolean(false)
                val notifiedFirstBatch = AtomicBoolean(false)
                var finalDetailsApplied = false
                var finalChaptersCompleted = false

                coroutineScope {
                    val progressChannel = if (listenComixProgress) Channel<String>(Channel.UNLIMITED) else null
                    val unregister = progressChannel?.let { channel ->
                        ComixProgressBridge.register { rawJson ->
                            if (!finalized.get()) channel.trySend(rawJson)
                        }
                    }
                    val progressJob = progressChannel?.let { channel ->
                        val sourcePrefs = context.getSharedPreferences("source_${source.id}", Context.MODE_PRIVATE)
                        launch(Dispatchers.IO) {
                            for (rawJson in channel) {
                                val parsed = parseComixProgress(rawJson, manga.url, sourcePrefs) ?: continue
                                callbackMutex.withLock {
                                    if (finalized.get()) return@withLock
                                    if (fetchDetails && !finalDetailsApplied && parsed.details != null) {
                                        awaitUpdateFromSource(
                                            currentManga,
                                            parsed.details,
                                            manualFetch,
                                            fetchChapters,
                                        )
                                        currentManga = mangaRepository.getMangaById(manga.id)
                                    }
                                    if (fetchChapters && parsed.chapters.isNotEmpty()) {
                                        receivedProgressChunks.set(true)
                                        val session = syncSession ?: syncChaptersWithSource.startSession(
                                            manga = currentManga,
                                            source = source,
                                            manualFetch = manualFetch,
                                            fetchWindow = fetchWindow,
                                        ).also { syncSession = it }
                                        session.addChunk(parsed.chapters, isFinal = false)
                                    }
                                    if ((!fetchChapters || parsed.chapters.isNotEmpty()) &&
                                        notifiedFirstBatch.compareAndSet(false, true)
                                    ) {
                                        onFirstBatchLoaded(fetchChapters && parsed.hasMore)
                                    }
                                }
                            }
                        }
                    }

                    try {
                        source.getMangaUpdateProgressively(
                            manga = manga.toSManga(),
                            chapters = knownChapters,
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters,
                            onDetails = { remoteManga ->
                                if (!fetchDetails) return@getMangaUpdateProgressively
                                callbackMutex.withLock {
                                    finalDetailsApplied = true
                                    awaitUpdateFromSource(currentManga, remoteManga, manualFetch, fetchChapters)
                                    currentManga = mangaRepository.getMangaById(manga.id)
                                }
                            },
                            onChaptersChunk = { chunk, isFinal ->
                                if (!fetchChapters) return@getMangaUpdateProgressively
                                if (isFinal) {
                                    finalized.set(true)
                                    unregister?.invoke()
                                    progressChannel?.close()
                                }
                                callbackMutex.withLock {
                                    if (isFinal) finalChaptersCompleted = true
                                    val session = syncSession ?: syncChaptersWithSource.startSession(
                                        manga = currentManga,
                                        source = source,
                                        manualFetch = manualFetch,
                                        fetchWindow = fetchWindow,
                                    ).also { syncSession = it }
                                    val synced = session.addChunk(
                                        rawSourceChapters = chunk,
                                        isFinal = isFinal,
                                        reconcileFinal = isFinal && receivedProgressChunks.get(),
                                    )
                                    if (synced != null) {
                                        newChapters = synced
                                    }
                                }
                            },
                        )
                    } finally {
                        finalized.set(true)
                        unregister?.invoke()
                        progressChannel?.close()
                        progressJob?.cancel()
                    }
                }
                if (isComix && fetchChapters && finalChaptersCompleted) {
                    markComixChaptersComplete(manga.id)
                }
                return newChapters
            }

            suspend fun executeUpdate(): List<Chapter> {
                return if (isComix) {
                    ComixRequestGate.withPacing { runOnce() }
                } else {
                    runOnce()
                }
            }

            val newChapters = executeUpdate()

            val updatedManga = mangaRepository.getMangaById(manga.id)

            Result.success(RemoteMangaUpdate(manga = updatedManga, newChapters = newChapters))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    private suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
        fetchChapters: Boolean,
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite (or 'update titles' preference is enabled), set its title from source and update in db
        val title =
            if (remoteTitle.isNotEmpty() && (!localManga.favorite || libraryPreferences.updateMangaTitles.get())) {
                remoteTitle
            } else {
                null
            }

        val coverLastModified = when {
            // Never refresh covers if the url is empty to avoid "losing" existing covers
            remoteManga.thumbnail_url.isNullOrEmpty() -> null
            !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
            localManga.isLocal() -> Clock.System.now()
            localManga.hasCustomCover(coverCache) -> {
                coverCache.deleteFromCache(localManga, false)
                null
            }
            else -> {
                coverCache.deleteFromCache(localManga, false)
                Clock.System.now()
            }
        }

        val thumbnailUrl = remoteManga.thumbnail_url?.takeIf { it.isNotEmpty() }
        val baseMemo = remoteManga.memo
        val mergedMemo = if (!fetchChapters &&
            localManga.memo[COMIX_COMPLETE_MEMO_KEY]?.jsonPrimitive?.booleanOrNull == true
        ) {
            buildJsonObject {
                baseMemo.forEach { (k, v) -> put(k, v) }
                put(COMIX_COMPLETE_MEMO_KEY, JsonPrimitive(true))
            }
        } else {
            baseMemo
        }

        val success = mangaRepository.updateRemote(
            MangaRemoteUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author,
                artist = remoteManga.artist,
                description = remoteManga.description,
                genre = remoteManga.getGenres(),
                thumbnailUrl = thumbnailUrl,
                status = remoteManga.status.toLong(),
                updateStrategy = remoteManga.update_strategy,
                initialized = true,
                memo = mergedMemo,
            ),
        )
        if (success && title != null) {
            downloadManager.renameManga(localManga, title)
        }
        return success
    }

    private suspend fun markComixChaptersComplete(mangaId: Long) {
        val current = mangaRepository.getMangaById(mangaId)
        if (current.memo[COMIX_COMPLETE_MEMO_KEY]?.jsonPrimitive?.booleanOrNull == true) return
        val updatedMemo = buildJsonObject {
            current.memo.forEach { (k, v) -> put(k, v) }
            put(COMIX_COMPLETE_MEMO_KEY, JsonPrimitive(true))
        }
        mangaRepository.updateRemote(
            MangaRemoteUpdate(
                id = current.id,
                title = null,
                author = null,
                artist = null,
                description = null,
                genre = null,
                status = current.status,
                thumbnailUrl = null,
                updateStrategy = current.updateStrategy,
                memo = updatedMemo,
                initialized = current.initialized,
                coverLastModified = null,
            ),
        )
    }

    private data class ParsedComixProgress(
        val details: SManga?,
        val chapters: List<SChapter>,
        val hasMore: Boolean,
    )

    private fun parseComixProgress(
        rawJson: String,
        mangaUrl: String,
        prefs: SharedPreferences,
    ): ParsedComixProgress? {
        val root = runCatching { COMIX_JSON.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val hid = root["hid"]?.jsonPrimitive?.contentOrNull
        val pathname = root["pathname"]?.jsonPrimitive?.contentOrNull
        if (!matchesComixManga(mangaUrl, hid, pathname)) return null

        val details = (root["detail"] as? JsonObject)?.let { parseComixDetails(it, mangaUrl, prefs) }
        val rawChapters = root["chapters"] as? JsonArray
        val chapters = if (rawChapters.isNullOrEmpty()) {
            emptyList()
        } else {
            parseComixChapters(rawChapters, mangaUrl, hid, pathname, prefs)
        }
        val hasMore = root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: true
        if (details == null && chapters.isEmpty()) return null
        return ParsedComixProgress(details = details, chapters = chapters, hasMore = hasMore)
    }

    private fun matchesComixManga(mangaUrl: String, hid: String?, pathname: String?): Boolean {
        val normManga = mangaUrl.trim().trimEnd('/').removePrefix("/")
        val normPath = pathname?.trim()?.trimEnd('/')?.removePrefix("/")
        if (!normPath.isNullOrEmpty() && normManga.equals(normPath, ignoreCase = true)) return true
        val mangaHid = COMIX_HID_REGEX.find(normManga)?.groupValues?.getOrNull(1)
        return !hid.isNullOrEmpty() && hid.equals(mangaHid, ignoreCase = true)
    }

    private fun parseComixDetails(detail: JsonObject, mangaUrl: String, prefs: SharedPreferences): SManga {
        val showTags = prefs.getBoolean("pref_show_tags_in_genres", false)
        val posterQuality = prefs.getString("pref_poster_quality", "large") ?: "large"

        return SManga.create().apply {
            url = mangaUrl
            detail["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { title = it }
            author = extractNamedList(detail["authors"] ?: detail["author"]).joinToString(", ").ifBlank { null }
            artist = extractNamedList(detail["artists"] ?: detail["artist"]).joinToString(", ").ifBlank { null }
            description = detail["synopsis"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            status = when (detail["status"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)) {
                "releasing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                "on_hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            val poster = detail["poster"] as? JsonObject
            thumbnail_url = poster?.let {
                it[posterQuality]?.jsonPrimitive?.contentOrNull
                    ?: it["large"]?.jsonPrimitive?.contentOrNull
                    ?: it["medium"]?.jsonPrimitive?.contentOrNull
                    ?: it["small"]?.jsonPrimitive?.contentOrNull
            }
            val genreList = buildList {
                when (detail["type"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)) {
                    "manga" -> add("Manga")
                    "manhwa" -> add("Manhwa")
                    "manhua" -> add("Manhua")
                    null -> Unit
                    else -> add("Other")
                }
                addAll(extractNamedList(detail["genres"] ?: detail["genre"]))
                addAll(extractNamedList(detail["theme"]))
                addAll(extractNamedList(detail["demographics"]))
                if (showTags) addAll(extractNamedList(detail["tags"]))
                val rating = detail["contentRating"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)
                if (rating == "erotica" || rating == "pornographic") add("NSFW")
            }.distinct()
            genre = genreList.takeIf { it.isNotEmpty() }?.joinToString(", ")
            initialized = true
        }
    }

    private fun extractNamedList(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                is JsonObject -> (item["name"] ?: item["title"])?.jsonPrimitive?.contentOrNull?.trim()?.takeIf {
                    it.isNotEmpty()
                }
                else -> null
            }
        }
    }

    private fun parseComixChapters(
        rawChapters: JsonArray,
        mangaUrl: String,
        hid: String?,
        pathname: String?,
        prefs: SharedPreferences,
    ): List<SChapter> {
        val blacklist = prefs.getString("pref_scanlator_blacklist", "").orEmpty()
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filterTo(HashSet()) { it.isNotEmpty() }
        val deduplicate = prefs.getBoolean("pref_deduplicate_chapters", false)
        val nowMs = System.currentTimeMillis()
        val slugBody = (pathname ?: mangaUrl).trim().trimEnd('/').removePrefix("/").removePrefix("title/").ifBlank {
            hid.orEmpty()
        }

        val candidates = rawChapters.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val number = obj["number"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val rawUrl = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val votes = obj["votes"]?.jsonPrimitive?.intOrNull ?: 0
            val createdAt = obj["createdAtFormatted"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isOfficial = obj["isOfficial"]?.jsonPrimitive?.booleanOrNull ?: false
            val groupObj = obj["group"] as? JsonObject
            val groupId = groupObj?.get("id")?.jsonPrimitive?.intOrNull
            val groupName = groupObj?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val resolvedScanlator = groupName ?: if (isOfficial) "Official" else "Unknown"
            if (resolvedScanlator.trim().lowercase(Locale.ROOT) in blacklist) return@mapNotNull null

            val numStr = number.toString().removeSuffix(".0")
            val titleIdx = rawUrl.indexOf("/title/")
            val chapterUrl = if (titleIdx >= 0) {
                rawUrl.substring(titleIdx + 1)
            } else {
                "title/$slugBody/$id-chapter-$numStr"
            }

            SChapter.create().apply {
                url = chapterUrl
                name = if (rawName.isNotEmpty()) "Chapter $numStr: $rawName" else "Chapter $numStr"
                chapter_number = number.toFloat()
                scanlator = resolvedScanlator
                date_upload = parseComixRelativeDate(createdAt, nowMs)
                memo = buildJsonObject {
                    put("chapterId", id)
                    put("votes", votes)
                    put("official", isOfficial)
                    if (groupId != null) put("groupId", groupId)
                }
            }
        }

        if (!deduplicate) return candidates

        val bestByNumber = LinkedHashMap<Float, SChapter>(candidates.size)
        for (ch in candidates) {
            val prev = bestByNumber[ch.chapter_number]
            if (prev == null || preferComixChapter(ch, prev)) {
                bestByNumber[ch.chapter_number] = ch
            }
        }
        return bestByNumber.values.toList()
    }

    private fun preferComixChapter(candidate: SChapter, current: SChapter): Boolean {
        val cOfficial = candidate.memo["official"]?.jsonPrimitive?.booleanOrNull == true
        val curOfficial = current.memo["official"]?.jsonPrimitive?.booleanOrNull == true
        if (cOfficial != curOfficial) return cOfficial
        val cVotes = candidate.memo["votes"]?.jsonPrimitive?.intOrNull ?: 0
        val curVotes = current.memo["votes"]?.jsonPrimitive?.intOrNull ?: 0
        if (cVotes != curVotes) return cVotes > curVotes
        val cId = candidate.memo["chapterId"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val curId = current.memo["chapterId"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        return cId < curId
    }

    private fun parseComixRelativeDate(raw: String, nowMs: Long): Long {
        val m = COMIX_REL_DATE_REGEX.matchEntire(raw.trim()) ?: return 0L
        val count = m.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "s", "sec", "secs" -> 1_000L
            "m", "min", "mins" -> 60_000L
            "h", "hr", "hrs" -> 3_600_000L
            "d", "day", "days" -> 86_400_000L
            "w", "week", "weeks" -> 7L * 86_400_000L
            "mo", "mos", "month", "months" -> 30L * 86_400_000L
            "y", "yr", "yrs", "year", "years" -> 365L * 86_400_000L
            else -> return 0L
        }
        return nowMs - count * unitMs
    }

    internal companion object {
        const val COMIX_COMPLETE_MEMO_KEY = "luxiChaptersComplete"
        private val COMIX_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private val COMIX_HID_REGEX = Regex("""^title/([a-z0-9]+)(?:-|/|$)""", RegexOption.IGNORE_CASE)
        private val COMIX_REL_DATE_REGEX = Regex(
            """^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)\s+ago$""",
            RegexOption.IGNORE_CASE,
        )

        fun isComixIncompleteHistory(manga: Manga, chapters: List<Chapter>): Boolean {
            if (manga.memo[COMIX_COMPLETE_MEMO_KEY]?.jsonPrimitive?.booleanOrNull == true) return false
            val numbered = chapters
                .map { it.chapterNumber }
                .filter { it > 0.0 }
                .sorted()
            if (numbered.isEmpty()) return true
            val reachesStart = numbered.first() <= 1.0
            val hasGap = numbered.zipWithNext().any { (a, b) -> floor(b) - floor(a) > 1.0 }
            return !reachesStart || hasGap
        }
    }
}
