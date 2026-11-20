package mihon.domain.source.interactor

import android.os.SystemClock
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ComixRequestGate {

    private const val COOLDOWN_MS = 2_000L
    private val mutex = Mutex()

    @Volatile
    private var lastFinishedAtMs = 0L

    fun isComix(source: Source): Boolean {
        return source.name.equals("Comix", ignoreCase = true) ||
            (source as? HttpSource)?.baseUrl?.contains("comix.", ignoreCase = true) == true
    }

    suspend fun <T> withPacing(block: suspend () -> T): T {
        return mutex.withLock {
            val elapsed = SystemClock.elapsedRealtime() - lastFinishedAtMs
            if (lastFinishedAtMs > 0L && elapsed < COOLDOWN_MS) {
                delay(COOLDOWN_MS - elapsed)
            }
            try {
                block()
            } finally {
                lastFinishedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }
}
