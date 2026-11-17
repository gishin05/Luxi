package mihon.core.common.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObjectEmptyBytes = byteArrayOf(0x7B, 0x7D)

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty

fun JsonObject.toByteArray(): ByteArray {
    if (isEmpty()) return JsonObjectEmptyBytes
    return toString().encodeToByteArray()
}

fun ByteArray.toJsonObject(): JsonObject {
    if (isEmpty() || contentEquals(JsonObjectEmptyBytes)) return JsonObjectEmpty
    return Json.decodeFromString<JsonObject>(decodeToString())
}
