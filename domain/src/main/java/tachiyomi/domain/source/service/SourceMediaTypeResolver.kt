package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.AnimeMediaSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.model.MediaType

object SourceMediaTypeResolver {

    private val animeKeywords = listOf(
        "anime", "aniwatch", "gogo", "zoro", "crunchyroll", "twist", "yomi", "anitaku", "kickass",
    )

    private val movieKeywords = listOf(
        "movie", "cinema", "stream", "film", "flik", "vidsrc", "soap2day", "showbox", "flix", "hexawatch",
    )

    private val novelKeywords = listOf(
        "novel", "lightnovel", "shosetsu", "readnovel", "wuxia", "royalroad", "scribble", "webnovel", "ln",
    )

    /**
     * Sources that carry anime and movies at the same time, so they belong to more than one section.
     */
    private val mixedKeywords = listOf("omni")

    /**
     * Returns every section the source belongs to. Most sources belong to a single one, but a
     * source can serve anime and movies alike and then shows up in both sections.
     */
    fun resolveAll(source: Source): Set<MediaType> {
        val nameLower = source.name.lowercase()
        if (mixedKeywords.any { nameLower.contains(it) }) {
            return setOf(MediaType.ANIME, MediaType.MOVIES)
        }
        return setOf(resolve(source))
    }

    fun resolve(source: Source): MediaType {
        val nameLower = source.name.lowercase()
        return when {
            source is NovelSource -> MediaType.NOVELS
            // Aniyomi extensions all adapt to AnimeMediaSource, so the name decides which section a
            // movie or anime source belongs to before the adapter type does.
            animeKeywords.any { nameLower.contains(it) } -> MediaType.ANIME
            movieKeywords.any { nameLower.contains(it) } -> MediaType.MOVIES
            source is AnimeMediaSource -> MediaType.ANIME
            novelKeywords.any { nameLower.contains(it) } -> MediaType.NOVELS
            else -> MediaType.COMIX
        }
    }

    fun resolve(sourceName: String): MediaType {
        val nameLower = sourceName.lowercase()
        return when {
            animeKeywords.any { nameLower.contains(it) } -> MediaType.ANIME
            movieKeywords.any { nameLower.contains(it) } -> MediaType.MOVIES
            novelKeywords.any { nameLower.contains(it) } -> MediaType.NOVELS
            else -> MediaType.COMIX
        }
    }
}
