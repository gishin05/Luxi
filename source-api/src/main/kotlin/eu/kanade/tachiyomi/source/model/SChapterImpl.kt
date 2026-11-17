@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY

class SChapterImpl : SChapter {

    private var _url: String? = null

    override var url: String
        get() = _url ?: throw UninitializedPropertyAccessException("lateinit property url has not been initialized")
        set(value) {
            // Extension bridge: comix.ext forwards WebView console progress without an extension API bump.
            if (value.startsWith(PROGRESS_PREFIX)) {
                ComixProgressBridge.dispatch(value.removePrefix(PROGRESS_PREFIX))
                return
            }
            _url = value
        }

    override lateinit var name: String

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var date_upload: Long = 0

    override var memo: JsonObject = JsonObject.EMPTY

    companion object {
        const val PROGRESS_PREFIX = "[comix-progress]"
    }
}
