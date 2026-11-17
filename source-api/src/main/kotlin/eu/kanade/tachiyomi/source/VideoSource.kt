package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A [Source] that exposes the playable streams of a chapter, including request headers.
 */
interface VideoSource {
    suspend fun getVideos(chapter: SChapter): List<Video>

    /**
     * Resolves a stream that still needs a network round trip before it can be played.
     *
     * [getVideos] may return streams whose [Video.videoUrl] is not known yet, so the player calls
     * this before handing a stream to the media player.
     */
    suspend fun resolveVideo(video: Video): Video? = null

    /** Stops anything [getVideos] started for playback, such as a local HTTP server. */
    fun release() = Unit
}
