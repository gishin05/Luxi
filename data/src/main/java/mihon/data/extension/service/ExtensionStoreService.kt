package mihon.data.extension.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.decodeExtensionList
import mihon.data.extension.model.decodeExtensionStore
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

@Inject
@SingleIn(AppScope::class)
class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    suspend fun fetch(indexUrl: String): Result<ExtensionStore> = withContext(Dispatchers.IO) { fetchStore(indexUrl) }

    private suspend fun fetchStore(indexUrl: String): Result<ExtensionStore> {
        val normalizedUrl = indexUrl.trim()
        if (normalizedUrl.endsWith("/plugins.min.json")) {
            return Result.success(
                ExtensionStore(
                    indexUrl = normalizedUrl,
                    name = "LNReader (Novels)",
                    badgeLabel = "LNReader",
                    signingKey = "LNREADER_BUILTIN_NOVELS",
                    contact = ExtensionStore.Contact(
                        website = "https://github.com/LNReader/lnreader-plugins",
                        discord = null,
                    ),
                    isLegacy = true,
                    extensionListUrl = null,
                ),
            )
        }
        var updatedIndexUrl: String = normalizedUrl
        return try {
            val response = network.client.newCall(GET(updatedIndexUrl)).awaitSuccess()
            val store = response.body.source().decompressIfGzipped().use { source ->
                val networkStore = when (source.peekJsonMarkerByte()) {
                    // "[..."
                    0x5B.toByte() -> run {
                        if (!normalizedUrl.endsWith("/index.min.json") && !normalizedUrl.endsWith("/index.json")) {
                            throw IllegalArgumentException("Provided legacy store url is not valid")
                        }
                        updatedIndexUrl = normalizedUrl
                            .removeSuffix("/index.min.json")
                            .removeSuffix("/index.json") + "/repo.json"
                        network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().body.source().use {
                            json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
                        }
                    }
                    // "{..."
                    0x7B.toByte() -> try {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                    } catch (_: IllegalArgumentException) {
                        json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                    }
                    else -> protoBuf.decodeExtensionStore(source.readByteArray())
                }

                if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
                    return fetchStore(networkStore.indexV2)
                }

                networkStore.toExtensionStore(updatedIndexUrl)
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "Failed to add extension store '$updatedIndexUrl'"
            }
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> = withContext(Dispatchers.IO) {
        if (store.indexUrl.endsWith("/plugins.min.json")) {
            // Novel JS plugins are managed via LNReaderSourceManager, not APK installers.
            return@withContext Result.success(emptyList())
        }
        try {
            val extensions = if (store.extensionListUrl != null) {
                val response = network.client.newCall(GET(store.extensionListUrl!!)).awaitSuccess()
                response.body.source().decompressIfGzipped().use { source ->
                    when (source.peekJsonMarkerByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                        else -> protoBuf.decodeExtensionList(source.readByteArray())
                    }
                        .toAvailableExtensions(store)
                }
            } else if (!store.isLegacy) {
                val response = network.client.newCall(GET(store.indexUrl)).awaitSuccess()
                response.body.source().decompressIfGzipped().use { source ->
                    when (source.peekJsonMarkerByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                        else -> protoBuf.decodeExtensionStore(source.readByteArray())
                    }
                        .extensionList!!
                        .toAvailableExtensions(store)
                }
            } else {
                val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
                val response = network.client.newCall(GET("$storeBaseUrl/index.min.json")).awaitSuccess()
                response.body.source().use { source ->
                    json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                        .map { it.toAvailableExtension(store, storeBaseUrl) }
                }
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun BufferedSource.peekJsonMarkerByte(): Byte? {
        val peeked = peek()
        if (peeked.exhausted()) return null
        val first = peeked.readByte()
        if (first == 0x5B.toByte() || first == 0x7B.toByte()) return first
        val c0 = first.toInt() and 0xFF
        if (c0 != 0x20 && c0 != 0x09 && c0 != 0x0D && c0 != 0xEF) return first
        var check = c0
        if (check == 0xEF) {
            if (peeked.exhausted() || (peeked.readByte().toInt() and 0xFF) != 0xBB) return first
            if (peeked.exhausted() || (peeked.readByte().toInt() and 0xFF) != 0xBF) return first
            if (peeked.exhausted()) return first
            check = peeked.readByte().toInt() and 0xFF
        }
        while (check == 0x20 || check == 0x09 || check == 0x0A || check == 0x0D) {
            if (peeked.exhausted()) return first
            check = peeked.readByte().toInt() and 0xFF
        }
        val candidate = check.toByte()
        return if (candidate == 0x5B.toByte() || candidate == 0x7B.toByte()) candidate else first
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }
}
