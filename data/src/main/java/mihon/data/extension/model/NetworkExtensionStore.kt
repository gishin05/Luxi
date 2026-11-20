package mihon.data.extension.model

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.data.extension.model.NetworkExtensionStore.ContentWarning
import mihon.data.extension.model.NetworkExtensionStore.ExtensionList
import mihon.domain.extension.model.ExtensionStore
import eu.kanade.tachiyomi.extension.model.Extension as TachiyomiExtension
import mihon.domain.extension.model.ContentWarning as DomainContentWarning

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) : BaseNetworkExtensionStore {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        // @ProtoNumber(6) val contentWarning: ContentWarning = ContentWarning.SAFE,
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("Unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }

    override fun toExtensionStore(indexUrl: String): ExtensionStore {
        return ExtensionStore(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = badgeLabel,
            signingKey = signingKey.trim().lowercase(),
            contact = ExtensionStore.Contact(
                website = contact.website,
                discord = contact.discord,
            ),
            isLegacy = false,
            extensionListUrl = extensionListUrl,
        )
    }
}

// Aniyomi v2 index.pb encodes isTorrent at @ProtoNumber(8) and sources at @ProtoNumber(9),
// whereas Mihon encodes sources at @ProtoNumber(8).
@SuppressLint("UnsafeOptInUsageError")
@Serializable
internal data class NetworkAnimeExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: NetworkExtensionStore.Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    ) {
        fun toNetworkExtensionList(): NetworkExtensionStore.ExtensionList =
            NetworkExtensionStore.ExtensionList(extensions.map { it.toNetworkExtension() })
    }

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: NetworkExtensionStore.Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val isTorrent: Boolean = false,
        @ProtoNumber(9) val sources: List<NetworkExtensionStore.Source> = emptyList(),
    ) {
        fun toNetworkExtension(): NetworkExtensionStore.Extension =
            NetworkExtensionStore.Extension(
                name = name,
                packageName = packageName,
                resources = resources,
                extensionLib = extensionLib,
                versionCode = versionCode,
                versionName = versionName,
                contentWarning = contentWarning,
                sources = sources,
            )
    }

    fun toNetworkExtensionStore(): NetworkExtensionStore =
        NetworkExtensionStore(
            name = name,
            badgeLabel = badgeLabel,
            signingKey = signingKey,
            contact = contact,
            extensionList = extensionList?.toNetworkExtensionList(),
            extensionListUrl = extensionListUrl,
        )
}

fun ProtoBuf.decodeExtensionStore(bytes: ByteArray): NetworkExtensionStore {
    val mihonStore = runCatching { decodeFromByteArray<NetworkExtensionStore>(bytes) }.getOrNull()
    if (mihonStore != null) {
        val extensions = mihonStore.extensionList?.extensions
        if (extensions == null || extensions.isEmpty() || extensions.any { it.sources.isNotEmpty() }) {
            return mihonStore
        }
        return runCatching {
            decodeFromByteArray<NetworkAnimeExtensionStore>(bytes).toNetworkExtensionStore()
        }.getOrDefault(mihonStore)
    }
    return decodeFromByteArray<NetworkAnimeExtensionStore>(bytes).toNetworkExtensionStore()
}

fun ProtoBuf.decodeExtensionList(bytes: ByteArray): ExtensionList {
    val mihonList = runCatching { decodeFromByteArray<ExtensionList>(bytes) }.getOrNull()
    if (mihonList != null) {
        if (mihonList.extensions.isEmpty() || mihonList.extensions.any { it.sources.isNotEmpty() }) {
            return mihonList
        }
        return runCatching {
            decodeFromByteArray<NetworkAnimeExtensionStore.ExtensionList>(bytes).toNetworkExtensionList()
        }.getOrDefault(mihonList)
    }
    return decodeFromByteArray<NetworkAnimeExtensionStore.ExtensionList>(bytes).toNetworkExtensionList()
}

fun ExtensionList.toAvailableExtensions(store: ExtensionStore): List<TachiyomiExtension.Available> {
    return extensions.map { extension ->
        val lang = extension.sources.map { it.language }.toSet()
        val libVersion = extension.extensionLib.trim().toDoubleOrNull()
            ?: if (extension.packageName.contains(".animeextension.")) {
                extension.versionName.substringBefore('.').toDoubleOrNull() ?: 14.0
            } else {
                extension.versionName.substringBeforeLast('.').toDoubleOrNull() ?: 1.4
            }
        TachiyomiExtension.Available(
            name = extension.name,
            pkgName = extension.packageName,
            apkUrl = extension.resources.apkUrl,
            iconUrl = extension.resources.iconUrl,
            libVersion = libVersion,
            versionCode = extension.versionCode,
            versionName = extension.versionName,
            lang = if (lang.size == 1) lang.first() else "all",
            contentWarning = when (extension.contentWarning) {
                ContentWarning.SAFE -> DomainContentWarning.SAFE
                ContentWarning.MIXED -> DomainContentWarning.MIXED
                ContentWarning.NSFW -> DomainContentWarning.NSFW
                else -> DomainContentWarning.SAFE
            },
            sources = extension.sources.map { source ->
                TachiyomiExtension.Available.Source(
                    id = source.id,
                    name = source.name,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            },
            store = store,
        )
    }
}
