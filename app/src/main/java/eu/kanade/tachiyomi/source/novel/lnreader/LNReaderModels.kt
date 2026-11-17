package eu.kanade.tachiyomi.source.novel.lnreader

import kotlinx.serialization.Serializable

@Serializable
data class LNPluginMeta(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String? = null,
)

@Serializable
data class LNNovelItem(
    val name: String,
    val path: String,
    val cover: String = "",
)

@Serializable
data class LNChapterItem(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
)

@Serializable
data class LNNovelDetails(
    val name: String,
    val path: String,
    val cover: String = "",
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val chapters: List<LNChapterItem> = emptyList(),
    val totalPages: Int = 1,
)

@Serializable
data class LNPageChapters(
    val chapters: List<LNChapterItem> = emptyList(),
)
