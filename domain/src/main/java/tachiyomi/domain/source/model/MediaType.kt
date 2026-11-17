package tachiyomi.domain.source.model

enum class MediaType(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    COMIX("comix", "Comix", "Manga, Manhwa, Manhua"),
    ANIME("anime", "Anime", "Anime Series & Movies"),
    MOVIES("movies", "Movies", "Films & TV Shows"),
    NOVELS("novels", "Novels", "Light & Web Novels"),
    ;

    companion object {
        fun fromId(id: String?): MediaType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: COMIX
        }
    }
}
