package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Semaphore

internal class ComixRateLimitInterceptor : Interceptor {

    private val fairLock = Semaphore(1, true)
    private val stateLock = Any()
    private var nextAllowedAtMs = 0L
    private var cooldownUntilMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")

        val request = chain.request()
        if (!isComixHost(request.url.host)) {
            return chain.proceed(request)
        }

        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }

        var reservedSlotMs = 0L
        try {
            while (true) {
                if (call.isCanceled()) throw IOException("Canceled")
                val waitMs = synchronized(stateLock) {
                    maxOf(nextAllowedAtMs, cooldownUntilMs) - SystemClock.elapsedRealtime()
                }
                if (waitMs <= 0L) {
                    synchronized(stateLock) {
                        val slot = SystemClock.elapsedRealtime() + MIN_INTERVAL_MS
                        reservedSlotMs = slot
                        nextAllowedAtMs = slot
                    }
                    break
                }
                try {
                    Thread.sleep(minOf(waitMs, CANCELLATION_POLL_MS))
                } catch (e: InterruptedException) {
                    throw IOException(e)
                }
            }
        } finally {
            fairLock.release()
        }

        val response = chain.proceed(request)
        if (response.networkResponse == null) {
            synchronized(stateLock) {
                if (nextAllowedAtMs == reservedSlotMs) {
                    nextAllowedAtMs = 0L
                }
            }
        } else if (response.code == 429 || (response.code == 503 && response.header("Retry-After") != null)) {
            val retryAfterSeconds = response.header("Retry-After")
                ?.trim()
                ?.toLongOrNull()
                ?: DEFAULT_COOLDOWN_SECONDS
            val cooldownMs = retryAfterSeconds.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS) * 1_000L
            synchronized(stateLock) {
                cooldownUntilMs = maxOf(cooldownUntilMs, SystemClock.elapsedRealtime() + cooldownMs)
            }
        }
        return response
    }

    private fun isComixHost(host: String): Boolean {
        return host.equals("comix.to", ignoreCase = true) ||
            host.endsWith(".comix.to", ignoreCase = true) ||
            host.equals("comix.ws", ignoreCase = true) ||
            host.endsWith(".comix.ws", ignoreCase = true)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 800L
        const val CANCELLATION_POLL_MS = 250L
        const val MIN_COOLDOWN_SECONDS = 2L
        const val DEFAULT_COOLDOWN_SECONDS = 5L
        const val MAX_COOLDOWN_SECONDS = 15L
    }
}
