package eu.kanade.tachiyomi.source.model

import java.util.concurrent.CopyOnWriteArrayList

object ComixProgressBridge {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun register(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun dispatch(rawJson: String) {
        for (listener in listeners) {
            runCatching { listener(rawJson) }
        }
    }
}
