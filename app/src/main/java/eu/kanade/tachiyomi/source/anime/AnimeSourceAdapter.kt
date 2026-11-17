package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.source.AnimeMediaSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.CoverHttpSource
import eu.kanade.tachiyomi.source.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat

/**
 * Exposes an Aniyomi [AnimeSource] to the parts of the app that expect a Mihon [CatalogueSource].
 *
 * Anime entries are stored as manga and episodes as chapters, so the rest of the app (library,
 * history, browse) keeps working without a second database model. Playback resolves the actual
 * streams through [VideoSource].
 */
class AnimeSourceAdapter(
    private val animeSource: AnimeSource,
) : CatalogueSource, AnimeMediaSource, VideoSource, CoverHttpSource {

    override val id: Long get() = animeSource.id
    override val name: String get() = animeSource.name
    override val lang: String get() = animeSource.lang
    override val supportsLatest: Boolean get() = animeSource.supportsLatest

    override val coverHeaders: Headers?
        get() = (animeSource as? AnimeHttpSource)?.headers

    override val coverClient: OkHttpClient?
        get() = (animeSource as? AnimeHttpSource)?.client

    // Mirrors HttpSource so downloads use a readable source directory instead of the adapter hash.
    override fun toString(): String = "$name (${lang.uppercase()})"

    @Volatile
    private var httpServer: eu.kanade.tachiyomi.animesource.model.HttpServer? = null

    override fun getFilterList(): FilterList = AnimeFilterMapper.toFilterList(animeSource.getFilterList())

    override suspend fun getPopularManga(page: Int): MangasPage {
        return animeSource.getPopularAnime(page).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return animeSource.getLatestUpdates(page).toMangasPage()
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val animeFilters = AnimeFilterMapper.toAnimeFilterList(filters) ?: animeSource.getFilterList()
        return animeSource.getSearchAnime(page, query, animeFilters).toMangasPage()
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val sAnime = manga.toSAnime()
        val updateResult = runCatching {
            animeSource.getAnimeEpisodeUpdate(
                anime = sAnime,
                episodes = chapters.map { it.toSEpisode() },
                fetchDetails = fetchDetails,
                fetchEpisodes = fetchChapters,
            )
        }
        val updateError = updateResult.exceptionOrNull()
        if (updateError is CancellationException) throw updateError

        if (updateError != null) {
            if (fetchChapters && MoviesModCompat.isMoviesMod(animeSource)) {
                val details = if (fetchDetails) {
                    runCatching { animeSource.getAnimeDetails(sAnime).toSManga() }.getOrElse { manga }
                } else {
                    manga
                }
                val compatEpisodes = MoviesModCompat.fetchEpisodes(animeSource, sAnime)
                val resolvedChapters = if (compatEpisodes.isNotEmpty()) {
                    compatEpisodes.map { it.toSChapter() }
                } else {
                    fallbackMovieChapters(manga)
                }
                return SMangaUpdate(manga = details, chapters = resolvedChapters)
            }
            throw updateError
        }

        val update = updateResult.getOrThrow()
        val resolvedChapters = if (fetchChapters && update.episodes.isEmpty()) {
            resolveChaptersWithCompat(manga, sAnime, allowEmptyOnFailure = true)
        } else {
            update.episodes.map { it.toSChapter() }
        }
        return SMangaUpdate(
            manga = update.anime.toSManga(),
            chapters = resolvedChapters,
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun getMangaUpdateProgressively(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        onDetails: suspend (SManga) -> Unit,
        onChaptersChunk: suspend (chapters: List<SChapter>, isFinal: Boolean) -> Unit,
    ) {
        if (!animeSource.overridesGetAnimeEpisodeUpdate()) {
            coroutineScope {
                val sAnime = manga.toSAnime()
                if (fetchDetails) {
                    launch {
                        onDetails(animeSource.getAnimeDetails(sAnime).toSManga())
                    }
                }
                if (fetchChapters) {
                    launch {
                        onChaptersChunk(resolveChaptersWithCompat(manga, sAnime, allowEmptyOnFailure = false), true)
                    }
                }
            }
            return
        }

        val update = getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        if (fetchDetails) {
            onDetails(update.manga)
        }
        if (fetchChapters) {
            onChaptersChunk(update.chapters, true)
        }
    }

    private suspend fun resolveChaptersWithCompat(
        manga: SManga,
        sAnime: SAnime,
        allowEmptyOnFailure: Boolean,
    ): List<SChapter> {
        val episodes = resolveEpisodesWithCompat(sAnime, allowEmptyOnFailure)
        return if (episodes.isNotEmpty()) {
            episodes.map { it.toSChapter() }
        } else {
            fallbackMovieChapters(manga)
        }
    }

    private fun fallbackMovieChapters(manga: SManga): List<SChapter> = listOf(
        SChapter.create().apply {
            url = manga.url
            name = manga.title.ifBlank { "Movie" }
            chapter_number = 1f
        },
    )

    private suspend fun resolveEpisodesWithCompat(
        anime: SAnime,
        allowEmptyOnFailure: Boolean,
    ): List<SEpisode> {
        val initial = runCatching { animeSource.getEpisodeList(anime) }
        val error = initial.exceptionOrNull()
        if (error is CancellationException) throw error
        val episodes = initial.getOrNull().orEmpty()
        if (episodes.isNotEmpty()) return episodes

        if (MoviesModCompat.isMoviesMod(animeSource)) {
            val compatResult = runCatching { MoviesModCompat.fetchEpisodes(animeSource, anime) }
            val compatErr = compatResult.exceptionOrNull()
            if (compatErr is CancellationException) throw compatErr
            val compatEpisodes = compatResult.getOrNull().orEmpty()
            if (compatEpisodes.isNotEmpty()) return compatEpisodes
            if (error != null && !allowEmptyOnFailure) {
                throw (compatErr ?: error)
            }
            return emptyList()
        }
        if (!allowEmptyOnFailure && error != null) throw error
        return emptyList()
    }

    // Anime chapters are played by PlayerActivity; the reader has nothing to show for them.
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    override suspend fun getVideos(chapter: SChapter): List<Video> {
        val source = animeSource as? AnimeHttpSource ?: return emptyList()
        val initialEpisode = chapter.toSEpisode()
        val isMoviesMod = MoviesModCompat.isMoviesMod(animeSource)

        if (isMoviesMod && MoviesModCompat.isCompatEpisodeUrl(initialEpisode.url)) {
            return MoviesModCompat.fetchVideosFallback(animeSource, initialEpisode.url, emptyList())
        }

        var activeEpisode = initialEpisode
        val initialResult = runCatching { fetchVideoCandidates(source, activeEpisode) }
        val initialError = initialResult.exceptionOrNull()
        if (initialError is CancellationException) throw initialError

        var candidates = initialResult.getOrNull().orEmpty()
        var finalError: Throwable? = initialError

        // Only query getEpisodeList when direct resolution yields nothing for a non-JSON chapter URL,
        // avoiding bogus /play/... calls on normal sources like AnimePahe while preserving MoviesMod fallback.
        if (candidates.isEmpty() && !activeEpisode.url.trim().startsWith("{")) {
            val matching = resolveMatchingEpisode(chapter)
            if (matching != null && matching.url.isNotBlank() && matching.url != activeEpisode.url) {
                activeEpisode = matching
                if (isMoviesMod && MoviesModCompat.isCompatEpisodeUrl(activeEpisode.url)) {
                    return MoviesModCompat.fetchVideosFallback(animeSource, activeEpisode.url, emptyList())
                }
                val fallbackResult = runCatching { fetchVideoCandidates(source, activeEpisode) }
                val fallbackError = fallbackResult.exceptionOrNull()
                if (fallbackError is CancellationException) throw fallbackError
                candidates = fallbackResult.getOrNull().orEmpty()
                if (candidates.isNotEmpty()) {
                    finalError = null
                } else if (fallbackError != null) {
                    finalError = fallbackError
                }
            }
        }

        if (isMoviesMod && (candidates.isEmpty() || MoviesModCompat.hasZipOrUnresolvedVideos(candidates))) {
            val compatVideos = runCatching {
                MoviesModCompat.fetchVideosFallback(animeSource, activeEpisode.url, candidates)
            }.getOrNull().orEmpty()
            if (compatVideos.isNotEmpty()) {
                candidates = compatVideos
                finalError = null
            }
        }

        if (candidates.isEmpty()) {
            if (finalError != null) throw finalError
            return emptyList()
        }

        // Mirrors Aniyomi: an explicitly preferred video wins, otherwise keep the source's order.
        return candidates
            .filterNot { it.isTorrentUrl() }
            .distinctBy { it.videoUrl.ifBlank { it.videoTitle } }
            .sortedWith(
                compareByDescending<Video> { it.preferred }
                    .thenByDescending { it.videoUrl.isStreamableHost() },
            )
    }

    private suspend fun resolveMatchingEpisode(chapter: SChapter): SEpisode? {
        val episodes = try {
            val anime = SAnime.create().apply { url = chapter.url.ifBlank { "" } }
            resolveEpisodesWithCompat(anime, allowEmptyOnFailure = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        return episodes.find { it.episode_number == chapter.chapter_number }
            ?: episodes.firstOrNull()
    }

    private suspend fun fetchVideoCandidates(source: AnimeHttpSource, episode: SEpisode): List<Video> {
        val useHosterFirst = source.overridesHosterList()
        var primaryError: Throwable? = null

        val primary = try {
            if (useHosterFirst) {
                val hosters = source.getHosterList(episode).let { hosters -> source.run { hosters.sortHosters() } }
                if (hosters.isNotEmpty()) loadVideos(source, hosters) else emptyList()
            } else {
                source.getVideoList(episode)
                    .let { videos -> source.run { videos.sortVideos() } }
                    .filterNot { it.isTorrentUrl() }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            primaryError = e
            logcat(LogPriority.WARN, e) {
                if (useHosterFirst) {
                    "Failed to load hosters for ${source.name}, trying video list"
                } else {
                    "Failed to load video list for ${source.name}, trying hosters"
                }
            }
            emptyList()
        }

        if (primary.isNotEmpty()) return primary

        val secondary = try {
            if (useHosterFirst) {
                source.getVideoList(episode)
                    .let { videos -> source.run { videos.sortVideos() } }
                    .filterNot { it.isTorrentUrl() }
            } else {
                val hosters = source.getHosterList(episode).let { hosters -> source.run { hosters.sortHosters() } }
                if (hosters.isNotEmpty()) loadVideos(source, hosters) else emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) {
                if (useHosterFirst) {
                    "Fallback getVideoList failed for ${source.name}"
                } else {
                    "Fallback getHosterList failed for ${source.name}"
                }
            }
            if (primaryError != null) throw primaryError
            if (e is IllegalStateException || e is UnsupportedOperationException) return emptyList()
            throw e
        }

        if (secondary.isNotEmpty()) return secondary
        if (primaryError != null) throw primaryError
        return emptyList()
    }

    override suspend fun resolveVideo(video: Video): Video? {
        val source = animeSource as? AnimeHttpSource ?: return null
        if (MoviesModCompat.isCompatVideoUrl(video.videoUrl)) {
            val compatResolved = MoviesModCompat.resolvePlayableVideo(source, video) ?: return null
            return unwrapDirectStream(source, compatResolved)
        }
        val resolved = resolveVideo(source, video) ?: return null
        val finalVideo = if (MoviesModCompat.isMoviesMod(animeSource) &&
            MoviesModCompat.isCompatVideoUrl(resolved.videoUrl)
        ) {
            MoviesModCompat.resolvePlayableVideo(source, resolved) ?: return null
        } else {
            resolved
        }
        return unwrapDirectStream(source, finalVideo)
    }

    private suspend fun loadVideos(source: AnimeHttpSource, hosters: List<Hoster>): List<Video> {
        if (hosters.isEmpty()) return emptyList()
        val nonLazy = hosters.filterNot { it.lazy }
        val lazy = hosters.filter { it.lazy }

        suspend fun loadHosterGroup(group: List<Hoster>): List<Video> {
            if (group.isEmpty()) return emptyList()
            return supervisorScope {
                group.map { hoster ->
                    async {
                        try {
                            val videos = hoster.videoList?.takeIf { it.isNotEmpty() } ?: source.getVideoList(hoster)
                            videos.let { sorted -> source.run { sorted.sortVideos() } }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "Failed to load videos for hoster ${hoster.hosterName}" }
                            emptyList()
                        }
                    }
                }.awaitAll().flatten().filterNot { it.isTorrentUrl() }
            }
        }

        val nonLazyVideos = loadHosterGroup(nonLazy)
        if (nonLazyVideos.isNotEmpty()) {
            return nonLazyVideos
        }
        return loadHosterGroup(lazy)
    }

    private suspend fun resolveVideo(source: AnimeHttpSource, video: Video): Video? {
        val resolved = if (video.initialized) video else source.resolveVideo(video) ?: return null
        val withUrl = if (resolved.videoUrl.isBlank() || resolved.videoUrl == NULL_URL) {
            val url = try {
                source.getVideoUrl(resolved)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Failed to get video url for ${resolved.videoTitle}" }
                null
            }
            if (!url.isNullOrBlank() && url != NULL_URL) {
                resolved.copy(videoUrl = url)
            } else {
                resolved
            }
        } else {
            resolved
        }
        if (withUrl.videoUrl.isBlank() || withUrl.videoUrl == NULL_URL || withUrl.isTorrentUrl()) return null
        return startHttpServer(source, withUrl.copy(initialized = true))
    }

    private fun unwrapDirectStream(source: AnimeHttpSource, video: Video): Video {
        val url = video.videoUrl
        if (url.isBlank()) return video

        val parsed = url.toHttpUrlOrNull()
        val isLoopbackProxy = parsed?.host?.lowercase() in LOOPBACK_HOSTS
        // Preserve extension-owned NanoHTTPD proxy URLs (e.g. AnimePahe /mp4?url=... or /m3u8?url=...)
        // because the proxy rewrites manifests/segments and injects upstream headers.
        val directParam = if (isLoopbackProxy) null else parsed?.queryParameter("url")
        if (!directParam.isNullOrBlank() && directParam.startsWith("http", ignoreCase = true)) {
            return video.copy(videoUrl = directParam)
        }

        if (url.contains("video-gen", ignoreCase = true)) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .headers(source.headers)
                    .header("Referer", "https://driveseed.org/")
                    .get()
                    .build()
                val noRedirectClient = source.client.newBuilder().followRedirects(false).build()
                noRedirectClient.newCall(request).execute().use { response ->
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        val directFromLocation = location.toHttpUrlOrNull()?.queryParameter("url")
                        if (!directFromLocation.isNullOrBlank() &&
                            directFromLocation.startsWith("http", ignoreCase = true)
                        ) {
                            return video.copy(videoUrl = directFromLocation)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Failed to unwrap video-gen redirect for ${video.videoTitle}" }
            }
        }

        return video
    }

    private fun startHttpServer(source: AnimeHttpSource, video: Video): Video {
        if (!video.usesHttpServer()) return video
        val server = httpServer ?: source.createHttpServer()?.also { it.start() }
        httpServer = server
        val port = server?.listeningPort ?: return video
        return if (port > 0) video.copyHttpServer(port) else video
    }

    override fun release() {
        httpServer?.stop()
        httpServer = null
    }

    private fun AnimeSource.overridesGetAnimeEpisodeUpdate(): Boolean {
        val method = runCatching {
            javaClass.getMethod(
                "getAnimeEpisodeUpdate",
                SAnime::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                kotlin.coroutines.Continuation::class.java,
            )
        }.getOrNull() ?: return true
        return method.declaringClass != eu.kanade.tachiyomi.animesource.AnimeCatalogueSource::class.java
    }

    private fun AnimeHttpSource.overridesHosterList(): Boolean {
        var current: Class<in AnimeHttpSource> = javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java || current == AnimeHttpSource::class.java) {
                return false
            }
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    private companion object {
        const val NULL_URL = "null"

        val HOSTER_METHODS = setOf("getHosterList", "hosterListRequest", "hosterListParse")
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}

private fun eu.kanade.tachiyomi.animesource.model.AnimesPage.toMangasPage(): MangasPage {
    return MangasPage(animes.map { it.toSManga() }, hasNextPage)
}

private fun SAnime.toSManga(): SManga = SManga.create().also {
    it.url = url
    it.title = title
    it.thumbnail_url = thumbnail_url
    it.artist = artist
    it.author = author
    it.status = status.takeIf { status -> status in SManga.UNKNOWN..SManga.ON_HIATUS } ?: SManga.UNKNOWN
    it.description = description
    it.genre = genre
    it.update_strategy = when (update_strategy) {
        AnimeUpdateStrategy.ONLY_FETCH_ONCE -> UpdateStrategy.ONLY_FETCH_ONCE
        AnimeUpdateStrategy.ALWAYS_UPDATE -> UpdateStrategy.ALWAYS_UPDATE
    }
    it.initialized = initialized
    it.memo = memo
}

private fun SManga.toSAnime(): SAnime = SAnime.create().also {
    it.url = url
    it.title = title
    it.thumbnail_url = thumbnail_url
    it.artist = artist
    it.author = author
    it.status = status
    it.description = description
    it.genre = genre
    it.update_strategy = when (update_strategy) {
        UpdateStrategy.ONLY_FETCH_ONCE -> AnimeUpdateStrategy.ONLY_FETCH_ONCE
        UpdateStrategy.ALWAYS_UPDATE -> AnimeUpdateStrategy.ALWAYS_UPDATE
    }
    it.fetch_type = FetchType.Episodes
    it.season_number = 0.0
    it.initialized = initialized
    it.memo = memo
}

private fun SEpisode.toSChapter(): SChapter = SChapter.create().also {
    it.url = url
    it.name = name
    it.chapter_number = episode_number
    it.scanlator = scanlator
    it.date_upload = date_upload
    it.memo = memo
}

private fun SChapter.toSEpisode(): SEpisode = SEpisode.create().also {
    it.url = url
    it.name = name
    it.episode_number = chapter_number
    it.scanlator = scanlator
    it.date_upload = date_upload
    it.memo = memo
}

private fun eu.kanade.tachiyomi.animesource.model.Video.isTorrentUrl(): Boolean {
    return videoUrl.startsWith("magnet:", ignoreCase = true) ||
        videoUrl.endsWith(".torrent", ignoreCase = true)
}

private fun String.isStreamableHost(): Boolean {
    if (isBlank()) return false
    if (contains("r2.dev", ignoreCase = true)) return false
    return contains("video-gen", ignoreCase = true) ||
        contains("video-seed", ignoreCase = true) ||
        contains("googleusercontent.com", ignoreCase = true) ||
        contains(".mkv", ignoreCase = true) ||
        contains(".mp4", ignoreCase = true) ||
        contains(".m3u8", ignoreCase = true)
}
