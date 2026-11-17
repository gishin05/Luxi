package eu.kanade.tachiyomi.source

import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Optional network customization for non-[eu.kanade.tachiyomi.source.online.HttpSource] sources
 * whose cover images need source-specific headers (e.g. Referer, User-Agent) or HTTP clients.
 */
interface CoverHttpSource {
    val coverHeaders: Headers?
        get() = null

    val coverClient: OkHttpClient?
        get() = null
}
