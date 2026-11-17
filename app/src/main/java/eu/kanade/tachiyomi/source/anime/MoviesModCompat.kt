package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.min

/**
 * Compatibility layer for MoviesMod so rate-limited modpro.blog archive pages and
 * Batch/Zip packs ("Only Zip Pack Available") resolve into streamable episodes and videos.
 */
object MoviesModCompat {

    private const val ZIP_SCHEME = "moviesmod-zip://"
    private const val MODLIST_HOLLYWOOD_URL = "https://mmodlist.org/?type=hollywood"
    private const val DEFAULT_BASE_URL = "https://moviesmod.tw"
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val BASE_URL_CACHE_MS = 10 * 60 * 1000L
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val ARCHIVE_CONCURRENCY = 2
    private const val ARCHIVE_PACING_MS = 180L
    private const val MAX_LINK_HOPS = 6

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val QUALITY_REGEX = Regex("\\d{3,4}p(?:\\s+\\w+)?", RegexOption.IGNORE_CASE)
    private val QUALITY_HEIGHT_REGEX = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
    private val SEASON_REGEX = Regex("[ .]?S(?:eason)?[ .]?(\\d{1,2})[ .]?", RegexOption.IGNORE_CASE)
    private val EPISODE_NAME_REGEX =
        Regex("(?:S\\d{1,2}\\s*)?(?:E|Ep(?:isode)?)[ ._-]*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
    private val TITLE_PREFIX_REGEX = Regex("^[^(]+\\n?")
    private val META_REFRESH_REGEX = Regex("url=(https?://[^\"'\\s>]+|[^\"'\\s>]+)", RegexOption.IGNORE_CASE)
    private val LOCATION_REPLACE_REGEX = Regex(
        "(?:location\\.replace|window\\.location(?:\\.href)?\\s*=)\\s*\\(?['\"]([^'\"]+)['\"]",
        RegexOption.IGNORE_CASE,
    )
    private val GO_URL_REGEX = Regex("(https?:[^\"'\\s<>]+?(?:\\?lp_go=|\\?go=|&lp_go=|&go=)[^\"'\\s<>]+)")

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedBaseUrlAtMs: Long = 0L

    @Serializable
    internal data class EpLinksPayload(
        val urls: List<EpUrlPayload>,
    )

    @Serializable
    internal data class EpUrlPayload(
        val quality: String,
        val url: String,
    )

    @Serializable
    internal data class ZipStreamDescriptor(
        val urls: List<String>,
        val name: String,
        val method: Int,
        val compSize: Long,
        val uncompSize: Long,
        val lfhOffset: Long,
        val headers: Map<String, String> = emptyMap(),
    )

    internal data class ArchiveButtonGroup(
        val title: String,
        val quality: String,
        val primaryUrls: List<String>,
        val batchUrls: List<String>,
    )

    internal data class ExtractedArchiveLink(
        val sectionTitle: String,
        val quality: String,
        val epLabel: String,
        val epNum: Int,
        val url: String,
        val isBatch: Boolean,
    )

    private data class UnpackedEpisodeItem(
        val sectionTitle: String,
        val epNum: Int,
        val qualityLabel: String,
        val url: String,
    )

    fun isMoviesMod(source: AnimeSource): Boolean {
        val pkg = source::class.java.`package`?.name.orEmpty()
        if (pkg.contains("moviesmod", ignoreCase = true)) return true
        return source.name.replace(" ", "").equals("MoviesMod", ignoreCase = true)
    }

    fun isCompatEpisodeUrl(chapterUrl: String): Boolean {
        return chapterUrl.contains(ZIP_SCHEME)
    }

    fun isCompatVideoUrl(videoUrl: String): Boolean {
        val trimmed = videoUrl.trim()
        return trimmed.startsWith(ZIP_SCHEME) || isRawZipUrl(trimmed)
    }

    fun hasZipOrUnresolvedVideos(videos: List<Video>): Boolean {
        return videos.any { isCompatVideoUrl(it.videoUrl) }
    }

    suspend fun fetchEpisodes(source: AnimeSource, anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val client = resolveClient(source)
        val headers = resolveHeaders(source)
        val initialBase = resolveActiveBaseUrl(source, client, headers)

        val postDoc = fetchPostDocumentWithDomainFallback(
            rawAnimeUrl = anime.url,
            initialBaseUrl = initialBase,
            client = client,
            headers = headers,
        )

        val groups = collectArchiveButtonGroups(postDoc)
        if (groups.isEmpty()) {
            throw IOException("No download or episode buttons found on ${postDoc.location()}")
        }

        val isSeries = detectIsSeries(groups, postDoc, anime.title)
        val semaphore = Semaphore(ARCHIVE_CONCURRENCY)

        val groupResults = coroutineScope {
            groups.mapIndexed { idx, group ->
                async {
                    if (idx > 0) delay(ARCHIVE_PACING_MS * (idx % 3))
                    semaphore.withPermit {
                        extractLinksForGroup(group, isSeries, client, headers)
                    }
                }
            }.awaitAll()
        }

        val directLinks = mutableListOf<ExtractedArchiveLink>()
        val batchFallbackLinks = mutableListOf<ExtractedArchiveLink>()
        for ((direct, batch) in groupResults) {
            directLinks += direct
            batchFallbackLinks += batch
        }

        val usableDirect = directLinks.filter { link ->
            !link.isBatch && (!isSeries || link.epNum > 0)
        }
        if (usableDirect.isNotEmpty()) {
            return@withContext buildStandardEpisodes(usableDirect, isSeries)
        }

        val batchCandidates = (directLinks + batchFallbackLinks).ifEmpty {
            throw IOException("Failed to load episode pages from MoviesMod.")
        }

        val unpacked = unpackBatchLinksToEpisodes(
            links = batchCandidates,
            isSeries = isSeries,
            client = client,
            headers = headers,
        )
        if (unpacked.isNotEmpty()) {
            return@withContext unpacked
        }

        buildStandardEpisodes(batchCandidates, isSeries = isSeries)
    }

    suspend fun fetchVideosFallback(
        source: AnimeSource,
        chapterUrl: String,
        existingVideos: List<Video>,
    ): List<Video> = withContext(Dispatchers.IO) {
        val client = resolveClient(source)
        val headers = resolveHeaders(source)

        val output = mutableListOf<Video>()
        for (existing in existingVideos) {
            when {
                existing.videoUrl.startsWith(ZIP_SCHEME) -> output += existing
                isRawZipUrl(existing.videoUrl) -> {
                    output += expandDirectZipUrlToVideos(
                        zipUrls = listOf(existing.videoUrl),
                        qualityLabel = existing.videoTitle,
                        headers = existing.headers ?: headers,
                        client = client,
                    )
                }
                existing.videoUrl.isNotBlank() -> {
                    val unwrapped = unwrapInstantRedirectUrl(existing.videoUrl)
                    output += if (unwrapped != existing.videoUrl) {
                        existing.copy(videoUrl = unwrapped, initialized = true)
                    } else {
                        existing
                    }
                }
            }
        }

        if (output.isNotEmpty() && existingVideos.none { it.videoUrl.isBlank() }) {
            return@withContext deduplicateVideos(output)
        }

        output += resolveVideosFromChapterPayload(chapterUrl, client, headers)
        deduplicateVideos(output)
    }

    suspend fun resolvePlayableVideo(
        source: AnimeSource,
        video: Video,
    ): Video? = withContext(Dispatchers.IO) {
        val client = resolveClient(source)
        val headers = video.headers ?: resolveHeaders(source)
        val url = video.videoUrl.trim()
        if (url.isBlank()) return@withContext null

        if (url.startsWith(ZIP_SCHEME)) {
            val descriptor = decodeZipDescriptor(url) ?: return@withContext null
            val mergedHeaders = mergeHeaders(headers, descriptor.headers)
            val entry = RemoteZipStreamProxy.ZipVideoEntry(
                name = descriptor.name,
                compressionMethod = descriptor.method,
                compressedSize = descriptor.compSize,
                uncompressedSize = descriptor.uncompSize,
                localHeaderOffset = descriptor.lfhOffset,
            )
            val proxyUrl = RemoteZipStreamProxy.shared.registerEntry(
                candidateUrls = descriptor.urls,
                headers = mergedHeaders,
                client = client,
                entry = entry,
            )
            return@withContext video.copy(
                videoUrl = proxyUrl,
                headers = null,
                initialized = true,
            )
        }

        if (isRawZipUrl(url)) {
            val inspection = runCatching {
                RemoteZipStreamProxy.inspectRemoteZip(client, headers, listOf(url))
            }.getOrNull() ?: return@withContext null
            val picked = pickMatchingZipEntry(inspection.entries, video.videoTitle) ?: return@withContext null
            val proxyUrl = RemoteZipStreamProxy.shared.registerEntry(
                candidateUrls = listOf(inspection.workingUrl, url),
                headers = headers,
                client = client,
                entry = picked,
            )
            return@withContext video.copy(
                videoUrl = proxyUrl,
                headers = null,
                initialized = true,
            )
        }

        val unwrapped = unwrapInstantRedirectUrl(url)
        if (unwrapped != url) {
            return@withContext video.copy(videoUrl = unwrapped, initialized = true)
        }

        video
    }

    internal suspend fun fetchPostDocumentWithDomainFallback(
        rawAnimeUrl: String,
        initialBaseUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): Document {
        val primaryUrl = resolveAgainstBase(rawAnimeUrl, initialBaseUrl)
        val firstAttempt = runCatching { fetchDocumentWithRetry(client, primaryUrl, headers) }
        val firstDoc = firstAttempt.getOrNull()
        if (firstDoc != null && collectArchiveButtonGroups(firstDoc).isNotEmpty()) {
            return firstDoc
        }

        val refreshedBase = fetchModListBaseUrl(client, headers)
        if (refreshedBase != null && !primaryUrl.startsWith(refreshedBase)) {
            val fallbackUrl = resolveAgainstBase(rawAnimeUrl, refreshedBase)
            val secondDoc = runCatching { fetchDocumentWithRetry(client, fallbackUrl, headers) }.getOrNull()
            if (secondDoc != null && collectArchiveButtonGroups(secondDoc).isNotEmpty()) {
                return secondDoc
            }
            if (secondDoc != null) return secondDoc
        }

        return firstDoc ?: throw (firstAttempt.exceptionOrNull() ?: IOException("Unable to load MoviesMod post"))
    }

    internal fun collectArchiveButtonGroups(doc: Document): List<ArchiveButtonGroup> {
        val seenElements = LinkedHashSet<Element>()
        val orderedParagraphs = mutableListOf<Element>()

        val primaryParagraphs = doc.select("p:has(a.maxbutton-episode-links, a.maxbutton-download-links)")
        val allMaxButtons = doc.select("p:has(a[class*=maxbutton])")
        for (p in primaryParagraphs) {
            if (seenElements.add(p)) orderedParagraphs.add(p)
        }
        for (p in allMaxButtons) {
            if (seenElements.add(p)) orderedParagraphs.add(p)
        }

        val groups = mutableListOf<ArchiveButtonGroup>()
        for (p in orderedParagraphs) {
            val anchors = p.select("a[href]")
                .mapNotNull { anchor ->
                    val rawHref = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.trim()
                    if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) {
                        return@mapNotNull null
                    }
                    val decoded = decodeUrlParamIfPresent(rawHref)
                    if (isIgnoredNavLink(decoded)) return@mapNotNull null
                    anchor.text().trim() to decoded
                }
            if (anchors.isEmpty()) continue

            val headingText = p.previousElementSiblings()
                .firstOrNull { !it.text().isBlank() }
                ?.text()
                .orEmpty()

            val quality = QUALITY_REGEX.find(headingText)?.value?.trim() ?: "HD"
            val cleanedTitle = buildSectionTitle(headingText, p)

            val primaryUrls = anchors
                .filter { (label, _) -> !isBatchOrZipLabel(label) }
                .map { it.second }
                .distinct()
            val batchUrls = anchors
                .filter { (label, _) -> isBatchOrZipLabel(label) }
                .map { it.second }
                .distinct()

            groups += ArchiveButtonGroup(
                title = cleanedTitle,
                quality = quality,
                primaryUrls = primaryUrls,
                batchUrls = batchUrls,
            )
        }
        return groups
    }

    private fun buildSectionTitle(headingText: String, paragraph: Element): String {
        val seasonMatch = SEASON_REGEX.find(headingText)?.value?.trim()
        val hasEpisodeOrBatchButton = paragraph.select("a").any { anchor ->
            val t = anchor.text()
            t.contains("episode", ignoreCase = true) || isBatchOrZipLabel(t)
        }
        if (seasonMatch != null || hasEpisodeOrBatchButton) {
            return seasonMatch ?: SEASON_REGEX.find(headingText)?.let { "Season ${it.groupValues[1]}" } ?: "Season 1"
        }
        val cleaned = headingText.replace("Download", "", ignoreCase = true).trim()
        return TITLE_PREFIX_REGEX.find(cleaned)?.value?.trim()?.ifBlank { null } ?: "Movie"
    }

    private fun detectIsSeries(groups: List<ArchiveButtonGroup>, doc: Document, animeTitle: String): Boolean {
        val anySeriesButton = doc.select("p:has(a[class*=maxbutton]) a").any { anchor ->
            val t = anchor.text()
            t.contains("Episode Links", ignoreCase = true) || isBatchOrZipLabel(t)
        }
        if (anySeriesButton) return true
        if (groups.any { SEASON_REGEX.containsMatchIn(it.title) || it.batchUrls.isNotEmpty() }) return true
        return SEASON_REGEX.containsMatchIn(animeTitle)
    }

    private suspend fun extractLinksForGroup(
        group: ArchiveButtonGroup,
        isSeries: Boolean,
        client: OkHttpClient,
        headers: Headers,
    ): Pair<List<ExtractedArchiveLink>, List<ExtractedArchiveLink>> {
        val direct = mutableListOf<ExtractedArchiveLink>()
        val batch = mutableListOf<ExtractedArchiveLink>()

        for (archiveUrl in group.primaryUrls) {
            val extracted = runCatching {
                fetchAndParseArchivePage(archiveUrl, group, isSeries, markBatch = false, client, headers)
            }.onFailure { e ->
                logcat(LogPriority.WARN, e) { "MoviesMod compat primary archive fetch failed: $archiveUrl" }
            }.getOrNull().orEmpty()
            for (item in extracted) {
                if (item.isBatch) batch += item else direct += item
            }
            if (direct.isNotEmpty()) break
        }

        // Only fetch the Batch/Zip archive URL if primary archive(s) yielded nothing or group is batch-only.
        val shouldCheckBatch = direct.isEmpty() && group.batchUrls.isNotEmpty()
        if (shouldCheckBatch) {
            for (batchUrl in group.batchUrls) {
                val extracted = runCatching {
                    fetchAndParseArchivePage(batchUrl, group, isSeries, markBatch = true, client, headers)
                }.onFailure { e ->
                    logcat(LogPriority.WARN, e) { "MoviesMod compat batch archive fetch failed: $batchUrl" }
                }.getOrNull().orEmpty()
                batch += extracted
                if (extracted.isNotEmpty()) break
            }
        }

        return direct to batch
    }

    private suspend fun fetchAndParseArchivePage(
        archiveUrl: String,
        group: ArchiveButtonGroup,
        isSeries: Boolean,
        markBatch: Boolean,
        client: OkHttpClient,
        headers: Headers,
    ): List<ExtractedArchiveLink> {
        val doc = fetchDocumentWithRetry(client, archiveUrl, headers)
        return parseArchiveDocument(doc, group, isSeries, markBatch)
    }

    internal fun parseArchiveDocument(
        doc: Document,
        groupTitle: String,
        groupQuality: String,
        isSeries: Boolean,
        markBatch: Boolean,
    ): List<ExtractedArchiveLink> {
        return parseArchiveDocument(
            doc = doc,
            group = ArchiveButtonGroup(
                title = groupTitle,
                quality = groupQuality,
                primaryUrls = emptyList(),
                batchUrls = emptyList(),
            ),
            isSeries = isSeries,
            markBatch = markBatch,
        )
    }

    private fun parseArchiveDocument(
        doc: Document,
        group: ArchiveButtonGroup,
        isSeries: Boolean,
        markBatch: Boolean,
    ): List<ExtractedArchiveLink> {
        val baseHost = doc.location().toHttpUrlOrNull()?.host
        val primaryAnchors = doc.select("div[class*=timed-content-client_show] a[href]")
            .filter { isPlayableArchiveAnchor(it, baseHost) }

        val anchors = if (primaryAnchors.isNotEmpty()) {
            primaryAnchors
        } else {
            doc.select(
                "a[href*=\"?sid=\"], a[href*=\"r?key=\"], a[href*=\"/l?\"], " +
                    "a[href*=\"driveseed\"], a[href*=\"driveleech\"], a[href*=\"gdrivepro\"], " +
                    "a[href*=\"hubcloud\"], a[href*=\"gdflix\"], a[href*=\"workers.dev\"], " +
                    "a[href*=\"r2.dev\"], a:has(button.buttonred)",
            ).filter { isPlayableArchiveAnchor(it, baseHost) }
        }

        return anchors.mapIndexedNotNull { idx, anchor ->
            val rawHref = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.trim()
            if (rawHref.isBlank()) return@mapIndexedNotNull null
            val href = decodeUrlParamIfPresent(rawHref)
            val label = anchor.text().trim()
            val hasZipServerBanner = doc.selectFirst("*:matchesOwn((?i)Zip/Pack Server|Copy Zip File)") != null
            val hasEpisodeLabel = EPISODE_NAME_REGEX.containsMatchIn(label)
            val batchAnchor = !hasEpisodeLabel && (markBatch || isBatchOrZipLabel(label) || hasZipServerBanner)

            val parsedEpNum = if (isSeries && !batchAnchor) {
                parseEpisodeNumber(label) ?: (idx + 1)
            } else {
                parseEpisodeNumber(label) ?: 0
            }

            val hostLabel = if (isSeries && !batchAnchor) {
                group.quality
            } else {
                "${group.quality} $label".trim()
            }

            ExtractedArchiveLink(
                sectionTitle = group.title,
                quality = hostLabel,
                epLabel = label,
                epNum = parsedEpNum,
                url = href,
                isBatch = batchAnchor,
            )
        }
    }

    private fun isPlayableArchiveAnchor(anchor: Element, archiveHost: String?): Boolean {
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.trim()
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return false
        val text = anchor.text().trim()
        if (text.contains("comment", ignoreCase = true) || text.equals("here", ignoreCase = true)) return false
        if (href.contains("#respond", ignoreCase = true) || href.contains("templatelens.com", ignoreCase = true)) {
            return false
        }
        val parsed = href.toHttpUrlOrNull() ?: return false
        if (parsed.host == archiveHost &&
            parsed.encodedQuery.isNullOrBlank() &&
            (parsed.encodedPath == "/" || parsed.encodedPath.isBlank())
        ) {
            return false
        }
        return !isIgnoredNavLink(href)
    }

    private fun buildStandardEpisodes(links: List<ExtractedArchiveLink>, isSeries: Boolean): List<SEpisode> {
        val grouped = linkedMapOf<Pair<String, Int>, MutableList<EpUrlPayload>>()
        for (link in links) {
            val key = link.sectionTitle to link.epNum
            grouped.getOrPut(key) { mutableListOf() }.add(
                EpUrlPayload(quality = link.quality, url = link.url),
            )
        }

        return grouped.entries.mapIndexed { idx, (key, urls) ->
            val (title, epNum) = key
            SEpisode.create().apply {
                url = json.encodeToString(EpLinksPayload(urls.distinctBy { it.url }))
                name = if (isSeries && epNum > 0) "$title Ep $epNum" else title
                episode_number = if (isSeries && epNum > 0) epNum.toFloat() else (idx + 1).toFloat()
                scanlator = if (isSeries) {
                    SEASON_REGEX.find(title)?.groupValues?.getOrNull(1)?.let { "Season $it" } ?: title
                } else {
                    null
                }
            }
        }.reversed()
    }

    private suspend fun unpackBatchLinksToEpisodes(
        links: List<ExtractedArchiveLink>,
        isSeries: Boolean,
        client: OkHttpClient,
        headers: Headers,
    ): List<SEpisode> {
        val items = mutableListOf<UnpackedEpisodeItem>()
        val completedSectionQuality = mutableSetOf<Pair<String, String>>()

        for (link in links) {
            val baseQuality = QUALITY_REGEX.find(link.quality)?.value ?: link.quality
            val sqKey = link.sectionTitle to baseQuality
            // One working mirror (e.g. Fast Server 1) already yields all ZIP entries for this section/quality.
            if (link.isBatch && sqKey in completedSectionQuality) continue

            val targets = runCatching { resolveLinkTargets(link.url, client, headers) }
                .onFailure { e -> logcat(LogPriority.WARN, e) { "MoviesMod compat link resolve failed: ${link.url}" } }
                .getOrNull().orEmpty()

            val linkItems = mutableListOf<UnpackedEpisodeItem>()
            for (target in targets) {
                if (target.contains("/list/", ignoreCase = true) || target.contains("/l?", ignoreCase = true)) {
                    val filePages = resolveDriveseedListToFiles(target, client, headers)
                    filePages.forEachIndexed { idx, (fileName, fileUrl) ->
                        val epNum = parseEpisodeNumber(fileName) ?: (idx + 1)
                        linkItems += expandSingleTargetToEpisodeItems(
                            sectionTitle = link.sectionTitle,
                            qualityLabel = baseQuality,
                            fallbackEpNum = epNum,
                            targetUrl = fileUrl,
                            isSeries = isSeries,
                            client = client,
                            headers = headers,
                        )
                    }
                } else {
                    val fallbackEp = if (link.epNum > 0) link.epNum else 1
                    linkItems += expandSingleTargetToEpisodeItems(
                        sectionTitle = link.sectionTitle,
                        qualityLabel = baseQuality,
                        fallbackEpNum = fallbackEp,
                        targetUrl = target,
                        isSeries = isSeries,
                        client = client,
                        headers = headers,
                    )
                }
            }

            if (linkItems.isNotEmpty()) {
                items += linkItems
                if (link.isBatch) {
                    completedSectionQuality += sqKey
                }
            }
        }

        if (items.isEmpty()) return emptyList()

        val grouped = linkedMapOf<Pair<String, Int>, MutableList<EpUrlPayload>>()
        for (item in items) {
            val key = (if (isSeries) item.sectionTitle else "Movie") to item.epNum
            grouped.getOrPut(key) { mutableListOf() }.add(
                EpUrlPayload(quality = item.qualityLabel, url = item.url),
            )
        }

        return grouped.entries.mapIndexed { idx, (key, urls) ->
            val (section, epNum) = key
            SEpisode.create().apply {
                url = json.encodeToString(EpLinksPayload(urls.distinctBy { it.url }))
                name =
                    if (isSeries) {
                        "$section Ep $epNum"
                    } else if (grouped.size >
                        1
                    ) {
                        "$section Part ${idx + 1}"
                    } else {
                        "Movie"
                    }
                episode_number = if (isSeries) epNum.toFloat() else (idx + 1).toFloat()
                scanlator = if (isSeries) {
                    SEASON_REGEX.find(section)?.groupValues?.getOrNull(1)?.let { "Season $it" } ?: section
                } else {
                    null
                }
            }
        }.reversed()
    }

    private suspend fun expandSingleTargetToEpisodeItems(
        sectionTitle: String,
        qualityLabel: String,
        fallbackEpNum: Int,
        targetUrl: String,
        isSeries: Boolean,
        client: OkHttpClient,
        headers: Headers,
    ): List<UnpackedEpisodeItem> {
        if (isRawZipUrl(targetUrl)) {
            return inspectZipToEpisodeItems(
                sectionTitle = sectionTitle,
                qualityLabel = qualityLabel,
                zipCandidateUrls = listOf(targetUrl),
                fallbackEpNum = fallbackEpNum,
                client = client,
                headers = headers,
            )
        }

        if (!targetUrl.contains("/file/", ignoreCase = true)) {
            return if (isVideoSeedOrDirectFile(targetUrl)) {
                listOf(
                    UnpackedEpisodeItem(sectionTitle, fallbackEpNum, qualityLabel, unwrapInstantRedirectUrl(targetUrl)),
                )
            } else {
                emptyList()
            }
        }

        val doc = runCatching { fetchDocumentWithRetry(client, targetUrl, headers) }.getOrNull() ?: return emptyList()
        val cardInfo = parseDriveseedCard(doc, targetUrl, client, headers)
        if (!cardInfo.isZip) {
            val firstDirect = cardInfo.directVideoUrls.firstOrNull() ?: return emptyList()
            return listOf(UnpackedEpisodeItem(sectionTitle, fallbackEpNum, qualityLabel, firstDirect))
        }

        val items = inspectZipToEpisodeItems(
            sectionTitle = sectionTitle,
            qualityLabel = qualityLabel,
            zipCandidateUrls = cardInfo.zipDownloadUrls,
            fallbackEpNum = fallbackEpNum,
            client = client,
            headers = headers,
        )
        if (items.isNotEmpty()) return items

        // If Range inspection failed, keep the driveseed /file/ URL so getVideos can retry when played.
        return listOf(
            UnpackedEpisodeItem(
                sectionTitle = sectionTitle,
                epNum = if (isSeries) fallbackEpNum else 1,
                qualityLabel = qualityLabel,
                url = targetUrl,
            ),
        )
    }

    private fun inspectZipToEpisodeItems(
        sectionTitle: String,
        qualityLabel: String,
        zipCandidateUrls: List<String>,
        fallbackEpNum: Int,
        client: OkHttpClient,
        headers: Headers,
    ): List<UnpackedEpisodeItem> {
        val inspection = runCatching {
            RemoteZipStreamProxy.inspectRemoteZip(client, headers, zipCandidateUrls)
        }.getOrNull() ?: return emptyList()

        val orderedUrls = listOf(inspection.workingUrl) + zipCandidateUrls.filter { it != inspection.workingUrl }
        val headerMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }

        return inspection.entries.mapIndexed { idx, entry ->
            val epNum = parseEpisodeNumber(entry.name) ?: if (inspection.entries.size > 1) idx + 1 else fallbackEpNum
            val encodedZipUrl = encodeZipDescriptor(
                ZipStreamDescriptor(
                    urls = orderedUrls,
                    name = entry.name,
                    method = entry.compressionMethod,
                    compSize = entry.compressedSize,
                    uncompSize = entry.uncompressedSize,
                    lfhOffset = entry.localHeaderOffset,
                    headers = headerMap,
                ),
            )
            UnpackedEpisodeItem(
                sectionTitle = sectionTitle,
                epNum = epNum,
                qualityLabel = "$qualityLabel [ZIP]".trim(),
                url = encodedZipUrl,
            )
        }
    }

    internal suspend fun resolveVideosFromChapterPayload(
        chapterUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<Video> {
        val entries = decodeEpisodePayload(chapterUrl)
        val out = mutableListOf<Video>()
        for (entry in entries) {
            if (entry.url.startsWith(ZIP_SCHEME)) {
                val desc = decodeZipDescriptor(entry.url)
                val qualityInt = parseQualityInt(entry.quality)
                val suffix = desc?.let { " • ${shortEntryLabel(it.name)}" } ?: ""
                out += Video(
                    videoUrl = entry.url,
                    videoTitle = "${entry.quality}$suffix".trim(),
                    resolution = qualityInt,
                    headers = headers,
                    initialized = true,
                )
            } else {
                out += resolveSingleUrlToVideos(entry.url, entry.quality, client, headers)
            }
        }
        return deduplicateVideos(out)
    }

    private suspend fun resolveSingleUrlToVideos(
        rawUrl: String,
        qualityLabel: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<Video> {
        val resolvedTargets = resolveLinkTargets(rawUrl, client, headers)
        val out = mutableListOf<Video>()
        for (target in resolvedTargets) {
            when {
                isRawZipUrl(target) -> {
                    out += expandDirectZipUrlToVideos(listOf(target), qualityLabel, headers, client)
                }
                isVideoSeedOrDirectFile(target) -> {
                    val direct = unwrapInstantRedirectUrl(target)
                    out += Video(
                        videoUrl = direct,
                        videoTitle = qualityLabel.ifBlank { "Direct" },
                        resolution = parseQualityInt(qualityLabel),
                        headers = headers,
                        initialized = true,
                    )
                }
                target.contains("/list/", ignoreCase = true) || target.contains("/l?", ignoreCase = true) -> {
                    val filePages = resolveDriveseedListToFiles(target, client, headers)
                    for ((fileName, fileUrl) in filePages) {
                        val label = if (fileName.isNotBlank()) "$qualityLabel • $fileName" else qualityLabel
                        out += extractDriveseedFileVideos(fileUrl, label, client, headers)
                    }
                }
                target.contains("/file/", ignoreCase = true) -> {
                    out += extractDriveseedFileVideos(target, qualityLabel, client, headers)
                }
            }
        }
        return out
    }

    private data class DriveseedCard(
        val fileName: String,
        val isZip: Boolean,
        val directVideoUrls: List<String>,
        val zipDownloadUrls: List<String>,
    )

    private suspend fun extractDriveseedFileVideos(
        fileUrl: String,
        qualityLabel: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<Video> {
        val doc = runCatching { fetchDocumentWithRetry(client, fileUrl, headers) }.getOrNull() ?: return emptyList()
        val card = parseDriveseedCard(doc, fileUrl, client, headers)

        if (card.isZip && card.zipDownloadUrls.isNotEmpty()) {
            return expandDirectZipUrlToVideos(
                zipUrls = card.zipDownloadUrls,
                qualityLabel = qualityLabel,
                headers = headers,
                client = client,
            )
        }

        return card.directVideoUrls.map { directUrl ->
            Video(
                videoUrl = directUrl,
                videoTitle = qualityLabel.ifBlank { card.fileName.ifBlank { "Direct" } },
                resolution = parseQualityInt(qualityLabel),
                headers = headers,
                initialized = true,
            )
        }
    }

    private suspend fun parseDriveseedCard(
        doc: Document,
        pageUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): DriveseedCard {
        // Handle intermediate JS redirect if driveseed /r or /l landed here cleanly via HTTP.
        val scriptRedirect = LOCATION_REPLACE_REGEX.find(doc.html())?.groupValues?.getOrNull(1)
        if (scriptRedirect != null) {
            val nextUrl = resolveUrl(pageUrl, scriptRedirect)
            if (nextUrl != pageUrl && nextUrl.contains("/file/", ignoreCase = true)) {
                val nextDoc = runCatching { fetchDocumentWithRetry(client, nextUrl, headers) }.getOrNull()
                if (nextDoc != null) return parseDriveseedCard(nextDoc, nextUrl, client, headers)
            }
        }

        val listItems = doc.select("li.list-group-item").map { it.text() }
        val fileName = listItems.firstOrNull { it.startsWith("Name", ignoreCase = true) }
            ?.substringAfter(':', "")?.trim().orEmpty()
        val format = listItems.firstOrNull { it.startsWith("Format", ignoreCase = true) }
            ?.substringAfter(':', "")?.trim().orEmpty()

        val directVideos = mutableListOf<String>()
        val zipMirrors = mutableListOf<String>()
        var wfileHref: String? = null
        var zfileHref: String? = null

        for (btn in doc.select("div.card-body a.btn[href], a.btn[href]")) {
            val absHref = btn.attr("abs:href").ifBlank { resolveUrl(pageUrl, btn.attr("href")) }.trim()
            if (absHref.isBlank() || absHref.contains("/login", true) || absHref.contains("t.me/", true) ||
                absHref.contains("seedtg.xyz", true)
            ) {
                continue
            }
            when {
                absHref.contains("/wfile/", ignoreCase = true) -> {
                    if (wfileHref == null) wfileHref = absHref
                }
                absHref.contains("/zfile/", ignoreCase = true) -> {
                    if (zfileHref == null) zfileHref = absHref
                }
                absHref.contains("cdn.video-gen.xyz", true) ||
                    absHref.contains("video-seed.dev", true) ||
                    absHref.contains("instant.video-gen", true) -> {
                    val resolved = runCatching { followRedirectsAndUnwrap(absHref, client, headers) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    if (resolved != null) {
                        if (isRawZipUrl(resolved)) zipMirrors += resolved else directVideos += resolved
                    }
                }
                absHref.contains("r2.dev", true) || absHref.contains("workers.dev", true) -> {
                    if (isRawZipUrl(absHref)) zipMirrors += absHref else directVideos += absHref
                }
            }
        }

        if (wfileHref != null) {
            val workerLinks = extractWfileWorkersLinks(wfileHref, client, headers)
            for (workerUrl in workerLinks) {
                if (isRawZipUrl(workerUrl)) zipMirrors += workerUrl else directVideos += workerUrl
            }
        }

        val isZip = format.equals("zip", ignoreCase = true) ||
            fileName.endsWith(".zip", ignoreCase = true) ||
            (zipMirrors.isNotEmpty() && directVideos.isEmpty())

        if (zfileHref != null && zipMirrors.isEmpty() && directVideos.isEmpty()) {
            val zfileDoc = runCatching { fetchDocumentWithRetry(client, zfileHref, headers) }.getOrNull()
            val zfileLinks = zfileDoc?.select("a[href*=workers.dev]")?.mapNotNull {
                it.attr("abs:href").ifBlank { null }
            }.orEmpty()
            zipMirrors += zfileLinks
        }

        return DriveseedCard(
            fileName = fileName,
            isZip = isZip,
            directVideoUrls = directVideos.distinct(),
            zipDownloadUrls = (zipMirrors + directVideos).distinct(),
        )
    }

    private suspend fun extractWfileWorkersLinks(
        wfileUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<String> {
        val doc = runCatching { fetchDocumentWithRetry(client, wfileUrl, headers) }.getOrNull() ?: return emptyList()
        val typeLinks = doc.select("a[href*='?type=']").mapNotNull { a ->
            val h = a.attr("abs:href").ifBlank { resolveUrl(doc.location(), a.attr("href")) }
            h.takeIf { it.isNotBlank() }
        }.ifEmpty { listOf(wfileUrl) }

        val workers = mutableListOf<String>()
        for (url in typeLinks) {
            val typeDoc = if (url == wfileUrl) {
                doc
            } else {
                runCatching { fetchDocumentWithRetry(client, url, headers) }.getOrNull()
            } ?: continue
            workers += typeDoc.select("div.mb-4 > a[href], a[href*=workers.dev]")
                .mapNotNull {
                    val href = it.attr("abs:href").trim()
                    if (href.isBlank() || href.contains("?type=", true) || href.contains("/login", true) ||
                        href.contains("t.me/", true)
                    ) {
                        null
                    } else {
                        href
                    }
                }
        }
        return workers.distinct()
    }

    private fun expandDirectZipUrlToVideos(
        zipUrls: List<String>,
        qualityLabel: String,
        headers: Headers,
        client: OkHttpClient,
    ): List<Video> {
        val inspection = runCatching {
            RemoteZipStreamProxy.inspectRemoteZip(client, headers, zipUrls)
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "ZIP inspection failed for ${zipUrls.firstOrNull()}" }
        }.getOrNull() ?: return emptyList()

        val orderedUrls = listOf(inspection.workingUrl) + zipUrls.filter { it != inspection.workingUrl }
        val headerMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
        val height = parseQualityInt(qualityLabel)

        return inspection.entries.map { entry ->
            val encoded = encodeZipDescriptor(
                ZipStreamDescriptor(
                    urls = orderedUrls,
                    name = entry.name,
                    method = entry.compressionMethod,
                    compSize = entry.compressedSize,
                    uncompSize = entry.uncompressedSize,
                    lfhOffset = entry.localHeaderOffset,
                    headers = headerMap,
                ),
            )
            val labelPrefix = qualityLabel.ifBlank { "ZIP" }
            val title = if (inspection.entries.size > 1) {
                "$labelPrefix • ${shortEntryLabel(entry.name)}"
            } else {
                labelPrefix
            }
            Video(
                videoUrl = encoded,
                videoTitle = title,
                resolution = height,
                headers = headers,
                initialized = true,
            )
        }
    }

    internal suspend fun resolveLinkTargets(
        rawUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<String> {
        var current = decodeUrlParamIfPresent(rawUrl.trim())
        repeat(MAX_LINK_HOPS) {
            when {
                current.contains("?sid=", ignoreCase = true) -> {
                    val solved = solveSidLanding(current, client, headers) ?: return emptyList()
                    current = solved
                }
                current.contains("r?key=", ignoreCase = true) ||
                    (current.contains("/l?", ignoreCase = true) && current.contains("key=", ignoreCase = true)) -> {
                    val doc = runCatching { fetchDocumentWithRetry(client, current, headers) }.getOrNull()
                        ?: return emptyList()
                    val redirect = extractMetaOrJsRedirect(doc, current) ?: return listOf(doc.location())
                    if (redirect == current) return listOf(current)
                    current = redirect
                }
                else -> return listOf(current)
            }
        }
        return listOf(current)
    }

    internal suspend fun solveSidLanding(
        sidUrl: String,
        client: OkHttpClient,
        baseHeaders: Headers,
    ): String? {
        val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                for (cookie in cookies) {
                    list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    list += cookie
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val now = System.currentTimeMillis()
                return cookieStore[url.host]?.filter { it.expiresAt >= now }.orEmpty()
            }
        }

        val sessionClient = client.newBuilder().cookieJar(jar).build()
        var currentUrl = sidUrl
        var doc = fetchDocumentWithRetry(sessionClient, currentUrl, baseHeaders)

        // Follow intermediate _lp_http / _lp_http2 WordPress landing forms before reading go/lp_go redirect.
        for (hop in 0 until 4) {
            val form = doc.selectFirst("form#landing, form[id^=lp-], form:has(input[name^=_lp_])") ?: break
            val formAction = form.absUrl("action").ifBlank { currentUrl }
            val formBody = FormBody.Builder().apply {
                for (input in form.select("input[name]")) {
                    val name = input.attr("name")
                    if (name.isNotBlank()) {
                        add(name, input.attr("value"))
                    }
                }
            }.build()

            val postHeaders = baseHeaders.newBuilder().set("Referer", currentUrl).build()
            val postReq = Request.Builder()
                .url(formAction)
                .headers(postHeaders)
                .post(formBody)
                .build()

            doc = executeToJsoupWithRetry(sessionClient, postReq)
            currentUrl = doc.location().ifBlank { formAction }
        }

        val normalizedHtml = doc.html().replace("\\/", "/")
        val goUrl = GO_URL_REGEX.find(normalizedHtml)?.groupValues?.getOrNull(1) ?: run {
            return extractMetaOrJsRedirect(doc, currentUrl)
        }

        val parsedGo = goUrl.toHttpUrlOrNull() ?: return null
        val paramName = listOf("lp_go", "go").firstOrNull { parsedGo.queryParameter(it) != null } ?: return null
        val cookieKey = parsedGo.queryParameter(paramName) ?: return null
        val cookiePattern = Regex("['\"]${Regex.escape(cookieKey)}['\"]\\s*,\\s*['\"]([^'\"]+)['\"]")
        val cookieVal = cookiePattern.find(normalizedHtml)?.groupValues?.getOrNull(1) ?: ""

        val goReq = Request.Builder()
            .url(parsedGo)
            .headers(
                baseHeaders.newBuilder()
                    .set("Referer", currentUrl)
                    .apply { if (cookieVal.isNotEmpty()) set("Cookie", "$cookieKey=$cookieVal") }
                    .build(),
            )
            .get()
            .build()

        val finalDoc = executeToJsoupWithRetry(sessionClient, goReq)
        return extractMetaOrJsRedirect(finalDoc, finalDoc.location())
    }

    private suspend fun resolveDriveseedListToFiles(
        listUrl: String,
        client: OkHttpClient,
        headers: Headers,
    ): List<Pair<String, String>> {
        val firstDoc =
            runCatching { fetchDocumentWithRetry(client, listUrl, headers) }.getOrNull() ?: return emptyList()
        val targetListUrl = if (!firstDoc.location().contains("/list/", ignoreCase = true)) {
            extractMetaOrJsRedirect(firstDoc, firstDoc.location()) ?: firstDoc.location()
        } else {
            firstDoc.location()
        }

        val listDoc = if (targetListUrl != firstDoc.location()) {
            runCatching { fetchDocumentWithRetry(client, targetListUrl, headers) }.getOrNull() ?: firstDoc
        } else {
            firstDoc
        }

        return listDoc.select("a[href*=/file/]").mapNotNull { anchor ->
            val href = anchor.attr("abs:href").ifBlank { resolveUrl(listDoc.location(), anchor.attr("href")) }
            val text = anchor.text().trim()
            if (href.isBlank()) null else (text to href)
        }.distinctBy { it.second }
    }

    private suspend fun followRedirectsAndUnwrap(
        url: String,
        client: OkHttpClient,
        headers: Headers,
    ): String {
        val unwrapped = unwrapInstantRedirectUrl(url)
        if (unwrapped != url) return unwrapped

        val req = Request.Builder().url(url).headers(headers).get().build()
        client.newCall(req).execute().use { resp ->
            val landed = resp.request.url.toString()
            val cleaned = unwrapInstantRedirectUrl(landed)
            if (cleaned != landed) return cleaned
            if (resp.isSuccessful && resp.header("Content-Type")?.contains("html", ignoreCase = true) == true) {
                val html = resp.body.string()
                val doc = Jsoup.parse(html, landed)
                val redirect = extractMetaOrJsRedirect(doc, landed)
                if (redirect != null) return unwrapInstantRedirectUrl(redirect)
            }
            return landed
        }
    }

    private suspend fun resolveActiveBaseUrl(
        source: AnimeSource,
        client: OkHttpClient,
        headers: Headers,
    ): String {
        val sourceBase = (source as? AnimeHttpSource)?.baseUrl?.trimEnd('/')
        if (!sourceBase.isNullOrBlank() && !sourceBase.contains("moviesmod.zone", ignoreCase = true)) {
            return sourceBase
        }
        return fetchModListBaseUrl(client, headers) ?: sourceBase ?: DEFAULT_BASE_URL
    }

    private suspend fun fetchModListBaseUrl(client: OkHttpClient, headers: Headers): String? {
        val now = System.currentTimeMillis()
        val existing = cachedBaseUrl
        if (existing != null && now - cachedBaseUrlAtMs < BASE_URL_CACHE_MS) {
            return existing
        }
        val doc = runCatching { fetchDocumentWithRetry(client, MODLIST_HOLLYWOOD_URL, headers) }.getOrNull()
            ?: return null
        val refresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content").orEmpty()
        val match = META_REFRESH_REGEX.find(refresh)?.groupValues?.getOrNull(1)?.trimEnd('/')
            ?: return null
        if (match.startsWith("http", ignoreCase = true)) {
            cachedBaseUrl = match
            cachedBaseUrlAtMs = now
            return match
        }
        return null
    }

    private suspend fun fetchDocumentWithRetry(
        client: OkHttpClient,
        url: String,
        headers: Headers,
    ): Document {
        val req = Request.Builder().url(url).headers(headers).get().build()
        return executeToJsoupWithRetry(client, req)
    }

    private suspend fun executeToJsoupWithRetry(client: OkHttpClient, request: Request): Document {
        var lastError: IOException? = null
        for (attempt in 0 until MAX_RETRY_ATTEMPTS) {
            var retryDelayMs: Long? = null
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 429 || resp.code == 503 || resp.code == 502) {
                        val retryAfterMs = resp.header("Retry-After")?.toLongOrNull()?.times(1000L)
                            ?: ((attempt + 1) * 700L)
                        lastError = IOException("HTTP ${resp.code} from ${request.url}")
                        if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                            retryDelayMs = min(retryAfterMs, 4000L)
                        } else {
                            throw lastError
                        }
                    } else if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} from ${request.url}")
                    } else {
                        val html = resp.body.string()
                        val finalUrl = resp.request.url.toString()
                        return Jsoup.parse(html, finalUrl)
                    }
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_RETRY_ATTEMPTS - 1 && retryDelayMs == null) {
                    retryDelayMs = (attempt + 1) * 600L
                }
            }
            if (retryDelayMs != null) {
                delay(retryDelayMs)
            }
        }
        throw lastError ?: IOException("Request failed for ${request.url}")
    }

    internal fun decodeEpisodePayload(chapterUrl: String): List<EpUrlPayload> {
        val trimmed = chapterUrl.trim()
        if (trimmed.startsWith("{")) {
            val parsed = runCatching { json.decodeFromString<EpLinksPayload>(trimmed) }.getOrNull()
            if (parsed != null && parsed.urls.isNotEmpty()) return parsed.urls
        }
        return listOf(EpUrlPayload(quality = "Direct", url = trimmed))
    }

    internal fun encodeZipDescriptor(descriptor: ZipStreamDescriptor): String {
        val raw = json.encodeToString(descriptor)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        return "$ZIP_SCHEME$b64"
    }

    internal fun decodeZipDescriptor(zipUri: String): ZipStreamDescriptor? {
        if (!zipUri.startsWith(ZIP_SCHEME)) return null
        val b64 = zipUri.removePrefix(ZIP_SCHEME)
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8)
            json.decodeFromString<ZipStreamDescriptor>(decoded)
        }.getOrNull()
    }

    internal fun decodeUrlParamIfPresent(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val encoded = parsed.queryParameter("url") ?: return url
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            val decoded = String(bytes, StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                decoded
            } else {
                url
            }
        }.getOrDefault(url)
    }

    internal fun unwrapInstantRedirectUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host.contains("video-seed.dev", ignoreCase = true) ||
            parsed.host.contains("video-gen", ignoreCase = true)
        ) {
            val inner = parsed.queryParameter("url")
            if (!inner.isNullOrBlank() && inner.startsWith("http", ignoreCase = true)) {
                return inner
            }
        }
        return url
    }

    internal fun extractMetaOrJsRedirect(doc: Document, currentUrl: String): String? {
        val metaContent = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content").orEmpty()
        val metaTarget = META_REFRESH_REGEX.find(metaContent)?.groupValues?.getOrNull(1)
        if (!metaTarget.isNullOrBlank()) {
            return resolveUrl(currentUrl, metaTarget)
        }
        val cleaned = doc.html().replace("\\/", "/")
        val jsTarget = LOCATION_REPLACE_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
        if (!jsTarget.isNullOrBlank() && jsTarget != "/404") {
            return resolveUrl(currentUrl, jsTarget)
        }
        return null
    }

    internal fun parseEpisodeNumber(text: String): Int? {
        val regexMatch = EPISODE_NAME_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (regexMatch != null) return regexMatch
        return text.replace("Episode", "", ignoreCase = true).trim().toIntOrNull()
    }

    internal fun isRawZipUrl(url: String): Boolean {
        if (url.startsWith(ZIP_SCHEME)) return false
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".zip", ignoreCase = true)
    }

    private fun isBatchOrZipLabel(label: String): Boolean {
        return label.contains("batch", ignoreCase = true) || label.contains("zip", ignoreCase = true)
    }

    private fun isVideoSeedOrDirectFile(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        return lower.contains("video-seed.dev") ||
            lower.contains("cdn.video-gen.xyz") ||
            lower.contains("instant.video-gen") ||
            lower.contains("r2.dev") ||
            lower.contains("workers.dev") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u8")
    }

    private fun isIgnoredNavLink(url: String): Boolean {
        val lower = url.lowercase()
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return lower.contains("t.me/") ||
            lower.contains("telegram") ||
            lower.contains("mmodlist.") ||
            lower.contains("imdb.com") ||
            (host.contains("moviesmod") && !lower.contains("?url="))
    }

    private fun pickMatchingZipEntry(
        entries: List<RemoteZipStreamProxy.ZipVideoEntry>,
        videoTitle: String,
    ): RemoteZipStreamProxy.ZipVideoEntry? {
        val epNum = parseEpisodeNumber(videoTitle)
        if (epNum != null) {
            val matched = entries.firstOrNull { parseEpisodeNumber(it.name) == epNum }
            if (matched != null) return matched
        }
        return entries.firstOrNull()
    }

    private fun shortEntryLabel(entryName: String): String {
        val leaf = entryName.substringAfterLast('/')
        return EPISODE_NAME_REGEX.find(leaf)?.value ?: leaf.take(36)
    }

    private fun parseQualityInt(label: String): Int? {
        return QUALITY_HEIGHT_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun resolveClient(source: AnimeSource): OkHttpClient {
        return (source as? AnimeHttpSource)?.let { runCatching { it.client }.getOrNull() } ?: OkHttpClient()
    }

    private fun resolveHeaders(source: AnimeSource): Headers {
        val existing = (source as? AnimeHttpSource)?.let { runCatching { it.headers }.getOrNull() }
        if (existing != null && existing["User-Agent"] != null) return existing
        return Headers.Builder()
            .apply { if (existing != null) addAll(existing) }
            .set("User-Agent", DEFAULT_USER_AGENT)
            .build()
    }

    private fun mergeHeaders(base: Headers, extra: Map<String, String>): Headers {
        if (extra.isEmpty()) return base
        return Headers.Builder().apply {
            addAll(base)
            for ((k, v) in extra) {
                if (k.isNotBlank() && v.isNotBlank()) set(k, v)
            }
        }.build()
    }

    private fun resolveAgainstBase(pathOrUrl: String, baseUrl: String): String {
        if (pathOrUrl.startsWith("http://", true) || pathOrUrl.startsWith("https://", true)) {
            val parsed = pathOrUrl.toHttpUrlOrNull()
            val targetBase = baseUrl.toHttpUrlOrNull()
            return if (parsed != null && targetBase != null && parsed.host != targetBase.host) {
                parsed.newBuilder().scheme(targetBase.scheme).host(targetBase.host).build().toString()
            } else {
                pathOrUrl
            }
        }
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = if (pathOrUrl.startsWith('/')) pathOrUrl else "/$pathOrUrl"
        return "$cleanBase$cleanPath"
    }

    private fun resolveUrl(base: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        return base.toHttpUrlOrNull()?.resolve(trimmed)?.toString() ?: trimmed
    }

    private fun deduplicateVideos(videos: List<Video>): List<Video> {
        return videos.distinctBy { "${it.videoUrl}|${it.videoTitle}" }
    }
}
