package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CoverHttpSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

class BakaTsukiNovelSource(
    private val networkHelper: NetworkHelper,
) : NovelSource, CoverHttpSource {

    override val id: Long = ID
    override val name: String = "Baka-Tsuki (Novels)"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override val coverHeaders: Headers
        get() = Headers.Builder()
            .add("User-Agent", networkHelper.defaultUserAgentProvider())
            .add("Referer", "$BASE_URL/")
            .build()

    override val coverClient: OkHttpClient
        get() = networkHelper.client

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .headers(coverHeaders)
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$API_URL?action=query&list=categorymembers" +
            "&cmtitle=Category:Light_novel_(English)&cmtype=page&cmlimit=40&format=json"
        val response = networkHelper.client.newCall(buildRequest(url)).execute()
        if (!response.isSuccessful) return MangasPage(emptyList(), false)

        val body = response.use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val members = root["query"]?.jsonObject?.get("categorymembers")?.jsonArray
            ?: return MangasPage(emptyList(), false)

        val titles = members.mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (title.startsWith("Category:") || title.startsWith("Template:")) return@mapNotNull null
            title
        }

        val coversByTitle = fetchCoverUrls(titles)
        val novels = titles.map { title ->
            SManga.create().apply {
                this.url = title.replace(" ", "_")
                this.title = title
                this.thumbnail_url = coversByTitle[title] ?: coversByTitle[this.url]
                this.status = SManga.ONGOING
                this.initialized = false
            }
        }
        return MangasPage(novels, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getPopularManga(page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$API_URL?action=query&list=search&srsearch=$encoded&srnamespace=0&srlimit=30&format=json"
        val response = networkHelper.client.newCall(buildRequest(url)).execute()
        if (!response.isSuccessful) return MangasPage(emptyList(), false)

        val body = response.use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val search = root["query"]?.jsonObject?.get("search")?.jsonArray ?: return MangasPage(emptyList(), false)

        val titles = search.mapNotNull { item ->
            item.jsonObject["title"]?.jsonPrimitive?.content
        }
        val coversByTitle = fetchCoverUrls(titles)

        val novels = titles.map { title ->
            SManga.create().apply {
                this.url = title.replace(" ", "_")
                this.title = title
                this.thumbnail_url = coversByTitle[title] ?: coversByTitle[this.url]
                this.status = SManga.ONGOING
                this.initialized = false
            }
        }
        return MangasPage(novels, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var latestManga = manga
        var latestChapters = chapters
        getMangaUpdateProgressively(
            manga = manga,
            chapters = chapters,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            onDetails = { latestManga = it },
            onChaptersChunk = { chunk, _ -> latestChapters = chunk },
        )
        return SMangaUpdate(latestManga, latestChapters)
    }

    override suspend fun getMangaUpdateProgressively(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        onDetails: suspend (SManga) -> Unit,
        onChaptersChunk: suspend (chapters: List<SChapter>, isFinal: Boolean) -> Unit,
    ) {
        val pageTitle = manga.url.replace(" ", "_")
        val encodedPage = URLEncoder.encode(pageTitle, "UTF-8")
        val url = "$API_URL?action=parse&page=$encodedPage&prop=text|images|categories" +
            "&disableeditsection=1&disabletoc=1&redirects=1&format=json"
        val response = networkHelper.client.newCall(buildRequest(url)).execute()

        val updatedManga = manga.copy().apply {
            initialized = true
        }
        if (!response.isSuccessful) {
            if (fetchDetails) onDetails(updatedManga)
            if (fetchChapters) onChaptersChunk(emptyList(), true)
            return
        }

        val body = response.use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val parseObj = root["parse"]?.jsonObject
        if (parseObj == null) {
            if (fetchDetails) onDetails(updatedManga)
            if (fetchChapters) onChaptersChunk(emptyList(), true)
            return
        }

        val html = parseObj["text"]?.jsonObject?.get("*")?.jsonPrimitive?.content.orEmpty()
        val doc = Jsoup.parse(html, BASE_URL)

        if (fetchDetails) {
            val queryCover = runCatching {
                val displayTitle = manga.title.ifBlank { pageTitle.replace("_", " ") }
                val map = fetchCoverUrls(listOf(displayTitle, pageTitle))
                map[displayTitle] ?: map[pageTitle]
            }.getOrNull()

            val htmlCover = queryCover ?: extractCoverFromHtml(doc)
            if (!htmlCover.isNullOrBlank()) {
                updatedManga.thumbnail_url = htmlCover
            }

            val synopsisP = doc.select("p").firstOrNull { p ->
                val text = p.text().trim()
                text.length > 30 && !text.startsWith("This page is", ignoreCase = true)
            }
            if (synopsisP != null) {
                updatedManga.description = synopsisP.text().trim()
            }

            val genres = parseObj["categories"]?.jsonArray
                ?.mapNotNull { cat ->
                    val raw = cat.jsonObject["*"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val readable = raw.replace("_", " ").trim()
                    if (readable.isBlank() || isAdminCategory(readable)) null else readable
                }
                ?.distinct()
                ?.take(8)
            if (!genres.isNullOrEmpty()) {
                updatedManga.genre = genres.joinToString(", ")
            }

            onDetails(updatedManga)
        }

        if (fetchChapters) {
            val chapterList = mutableListOf<SChapter>()
            val links = doc.select("a[href]")
            val seriesPrefix = manga.title.replace("_", " ")

            links.forEach { link ->
                val href = link.attr("href")
                val linkTitle = link.attr("title").trim()
                val text = link.text().trim()

                if (linkTitle.isNotBlank() && (linkTitle.startsWith(seriesPrefix) || href.contains(pageTitle))) {
                    if (!linkTitle.contains("(page does not exist)") &&
                        !linkTitle.startsWith("File:") &&
                        !linkTitle.startsWith("Category:")
                    ) {
                        chapterList.add(
                            SChapter.create().apply {
                                this.url = linkTitle.replace(" ", "_")
                                this.name = text.ifBlank { linkTitle }
                                this.chapter_number = (chapterList.size + 1).toFloat()
                                this.date_upload = System.currentTimeMillis()
                            },
                        )
                    }
                }
            }

            val distinctChapters = chapterList.distinctBy { it.url }.reversed()
            onChaptersChunk(distinctChapters, true)
        }
    }

    private fun fetchCoverUrls(titles: List<String>): Map<String, String> {
        if (titles.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()

        titles.chunked(25).forEach { batch ->
            val joined = batch.joinToString("|") { it.replace("_", " ") }
            val encoded = URLEncoder.encode(joined, "UTF-8")
            val url = "$API_URL?action=query&titles=$encoded" +
                "&prop=pageimages|images&piprop=thumbnail|original&pithumbsize=500&imlimit=10&redirects=1&format=json"

            val queryJson = runCatching {
                networkHelper.client.newCall(buildRequest(url)).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    json.parseToJsonElement(resp.body.string()).jsonObject
                }
            }.getOrNull() ?: return@forEach

            val (directCovers, candidateFilesByTitle) = parseQueryCoversAndFiles(queryJson)
            result.putAll(directCovers)

            val fileTitles = candidateFilesByTitle.values.distinct()
            if (fileTitles.isNotEmpty()) {
                val resolvedFiles = fetchFileImageUrls(fileTitles)
                candidateFilesByTitle.forEach { (title, fileTitle) ->
                    val resolved = resolvedFiles[fileTitle]
                    if (!resolved.isNullOrBlank()) {
                        result.putIfAbsent(title, resolved)
                        result.putIfAbsent(title.replace(" ", "_"), resolved)
                    }
                }
            }
        }
        return result
    }

    private fun fetchFileImageUrls(fileTitles: List<String>): Map<String, String> {
        if (fileTitles.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        fileTitles.chunked(25).forEach { batch ->
            val joined = batch.joinToString("|")
            val encoded = URLEncoder.encode(joined, "UTF-8")
            val url = "$API_URL?action=query&titles=$encoded&prop=imageinfo&iiprop=url&format=json"
            val root = runCatching {
                networkHelper.client.newCall(buildRequest(url)).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    json.parseToJsonElement(resp.body.string()).jsonObject
                }
            }.getOrNull() ?: return@forEach

            result.putAll(parseImageInfoUrls(root))
        }
        return result
    }

    override suspend fun getChapterContent(chapter: SChapter): String {
        val encodedPage = URLEncoder.encode(chapter.url.replace(" ", "_"), "UTF-8")
        val url = "$API_URL?action=parse&page=$encodedPage&prop=text" +
            "&disableeditsection=1&disabletoc=1&redirects=1&format=json"
        val response = networkHelper.client.newCall(buildRequest(url)).execute()
        if (!response.isSuccessful) return "<p>Failed to load chapter content.</p>"

        val body = response.use { it.body.string() }
        val root = json.parseToJsonElement(body).jsonObject
        val parseObj = root["parse"]?.jsonObject ?: return "<p>Failed to parse chapter content.</p>"
        val html = parseObj["text"]?.jsonObject?.get("*")?.jsonPrimitive?.content.orEmpty()
        return html.ifBlank { "<p>Chapter is empty.</p>" }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return listOf(Page(0, chapter.url, ""))
    }

    companion object {
        const val ID = 700000004L
        private const val BASE_URL = "https://www.baka-tsuki.org"
        private const val API_URL = "$BASE_URL/project/api.php"

        internal fun parseQueryCoversAndFiles(root: JsonObject): Pair<Map<String, String>, Map<String, String>> {
            val queryObj = root["query"] as? JsonObject ?: return emptyMap<String, String>() to emptyMap()
            val normalizedMap = buildMap {
                (queryObj["normalized"] as? JsonArray)?.forEach { entry ->
                    val obj = entry as? JsonObject ?: return@forEach
                    val from = obj.string("from")
                    val to = obj.string("to")
                    if (!from.isNullOrBlank() && !to.isNullOrBlank()) put(to, from)
                }
            }
            val redirectMap = buildMap {
                (queryObj["redirects"] as? JsonArray)?.forEach { entry ->
                    val obj = entry as? JsonObject ?: return@forEach
                    val from = obj.string("from")
                    val to = obj.string("to")
                    if (!from.isNullOrBlank() && !to.isNullOrBlank()) put(to, from)
                }
            }

            val covers = mutableMapOf<String, String>()
            val candidateFiles = mutableMapOf<String, String>()
            val pages = queryObj["pages"] as? JsonObject ?: return covers to candidateFiles

            pages.values.forEach { pageElem ->
                val pageObj = pageElem as? JsonObject ?: return@forEach
                val canonicalTitle = pageObj.string("title") ?: return@forEach
                val keys = buildList {
                    add(canonicalTitle)
                    add(canonicalTitle.replace(" ", "_"))
                    redirectMap[canonicalTitle]?.let { r ->
                        add(r)
                        add(r.replace(" ", "_"))
                        normalizedMap[r]?.let { n ->
                            add(n)
                            add(n.replace(" ", "_"))
                        }
                    }
                    normalizedMap[canonicalTitle]?.let { n ->
                        add(n)
                        add(n.replace(" ", "_"))
                    }
                }.distinct()

                val thumbUrl = (pageObj["thumbnail"] as? JsonObject)?.string("source")
                    ?: (pageObj["original"] as? JsonObject)?.string("source")
                val normalizedCover = normalizeCoverUrl(thumbUrl)
                if (!normalizedCover.isNullOrBlank()) {
                    keys.forEach { covers[it] = normalizedCover }
                    return@forEach
                }

                val bestFile = (pageObj["images"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.string("title") }
                    ?.filter { isValidCoverFileName(it) }
                    ?.sortedByDescending { scoreCoverFileName(it) }
                    ?.firstOrNull()
                if (!bestFile.isNullOrBlank()) {
                    keys.forEach { candidateFiles[it] = bestFile }
                }
            }
            return covers to candidateFiles
        }

        internal fun parseImageInfoUrls(root: JsonObject): Map<String, String> {
            val queryObj = root["query"] as? JsonObject ?: return emptyMap()
            val pages = queryObj["pages"] as? JsonObject ?: return emptyMap()
            val normalizedMap = buildMap {
                (queryObj["normalized"] as? JsonArray)?.forEach { entry ->
                    val obj = entry as? JsonObject ?: return@forEach
                    val from = obj.string("from")
                    val to = obj.string("to")
                    if (!from.isNullOrBlank() && !to.isNullOrBlank()) put(to, from)
                }
            }
            val result = mutableMapOf<String, String>()
            pages.values.forEach { pageElem ->
                val pageObj = pageElem as? JsonObject ?: return@forEach
                val title = pageObj.string("title") ?: return@forEach
                val url = (pageObj["imageinfo"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.string("url")
                val normalized = normalizeCoverUrl(url) ?: return@forEach
                result[title] = normalized
                normalizedMap[title]?.let { result[it] = normalized }
            }
            return result
        }

        private fun JsonObject.string(key: String): String? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            return primitive.content
        }

        internal fun extractCoverFromHtml(doc: org.jsoup.nodes.Document): String? {
            val candidates = doc.select(".infobox img, .thumbimage img, .floatright img, img")
            return candidates.asSequence()
                .mapNotNull { img ->
                    val src = img.absUrl("src").ifBlank { img.attr("src") }
                    normalizeCoverUrl(src)
                }
                .firstOrNull { isValidCoverFileName(it) }
        }

        private fun normalizeCoverUrl(url: String?): String? {
            val trimmed = url?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return when {
                trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "$BASE_URL$trimmed"
                else -> "$BASE_URL/$trimmed"
            }
        }

        private fun isValidCoverFileName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower.endsWith(".svg") || lower.contains(".svg/")) return false
            val ignored = listOf("icon", "flag", "logo", "poweredby", "button", "stub", "commons-", "edit-icon")
            if (ignored.any { lower.contains(it) }) return false
            return lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".png") ||
                lower.endsWith(".webp") ||
                lower.contains(".jpg/") ||
                lower.contains(".jpeg/") ||
                lower.contains(".png/") ||
                lower.contains(".webp/")
        }

        private fun scoreCoverFileName(name: String): Int {
            val lower = name.lowercase()
            return when {
                lower.contains("cover") -> 3
                lower.contains("v01") || lower.contains("vol1") || lower.contains("v1") -> 2
                else -> 1
            }
        }

        private fun isAdminCategory(category: String): Boolean {
            val lower = category.lowercase()
            return lower.startsWith("light novel") ||
                lower.contains("page") ||
                lower.contains("article") ||
                lower.contains("project") ||
                lower.contains("stub") ||
                lower == "english"
        }
    }
}
