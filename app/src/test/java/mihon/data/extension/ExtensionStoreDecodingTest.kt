package mihon.data.extension

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.decodeExtensionList
import mihon.data.extension.model.decodeExtensionStore
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class ExtensionStoreDecodingTest {

    private val protoBuf = ProtoBuf
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `decodes Aniyomi v2 index pb containing torrent extensions`() {
        val bytes = buildAnimeStorePb(
            signingKey = "CBEC121AA82EBB02AAA73806992E0368A97D47B5451ED6524816D03084C45905",
            extensions = listOf(
                buildAnimeExtPb(
                    name = "AnimeOnsen",
                    pkg = "eu.kanade.tachiyomi.animeextension.all.animeonsen",
                    lib = "16",
                    code = 12L,
                    version = "16.12",
                    isTorrent = false,
                    includeTorrentField = false,
                    sourceId = 8542735178285060053L,
                    sourceName = "AnimeOnsen",
                    sourceLang = "all",
                    homeUrl = "https://www.animeonsen.xyz",
                ),
                buildAnimeExtPb(
                    name = "Hentai Torrent (Torrent)",
                    pkg = "eu.kanade.tachiyomi.animeextension.all.hentaitorrent",
                    lib = "14",
                    code = 6L,
                    version = "14.6",
                    isTorrent = true,
                    includeTorrentField = true,
                    sourceId = 5093529078954574163L,
                    sourceName = "Hentai Torrent (Torrent)",
                    sourceLang = "all",
                    homeUrl = "https://www.hentaitorrents.com",
                ),
            ),
        )

        val decoded = protoBuf.decodeExtensionStore(bytes)
        val store = decoded.toExtensionStore("https://example.com/index.pb")
        store.signingKey shouldBe "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"

        val available = decoded.extensionList!!.toAvailableExtensions(store)
        available shouldHaveSize 2
        available[0].pkgName shouldBe "eu.kanade.tachiyomi.animeextension.all.animeonsen"
        available[0].libVersion shouldBe 16.0
        available[0].sources shouldHaveSize 1
        available[0].sources[0].id shouldBe 8542735178285060053L
        available[1].pkgName shouldBe "eu.kanade.tachiyomi.animeextension.all.hentaitorrent"
        available[1].sources shouldHaveSize 1
    }

    @Test
    fun `decodes Aniyomi v2 index pb without torrent extensions preserving field 9 sources`() {
        val bytes = buildAnimeStorePb(
            signingKey = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905",
            extensions = listOf(
                buildAnimeExtPb(
                    name = "AnimePahe",
                    pkg = "eu.kanade.tachiyomi.animeextension.en.animepahe",
                    lib = "16",
                    code = 59L,
                    version = "16.59",
                    isTorrent = false,
                    includeTorrentField = false,
                    sourceId = 123456789L,
                    sourceName = "AnimePahe",
                    sourceLang = "en",
                    homeUrl = "https://animepahe.si",
                ),
            ),
        )

        val decoded = protoBuf.decodeExtensionStore(bytes)
        val extensions = decoded.extensionList!!.extensions
        extensions shouldHaveSize 1
        extensions[0].sources shouldHaveSize 1
        extensions[0].sources[0].name shouldBe "AnimePahe"
        extensions[0].sources[0].language shouldBe "en"
    }

    @Test
    fun `decodes Aniyomi v2 external ExtensionList pb with and without torrent extensions`() {
        val listWithTorrent = buildExtensionListPb(
            listOf(
                buildAnimeExtPb(
                    name = "AniZone",
                    pkg = "eu.kanade.tachiyomi.animeextension.all.anizone",
                    lib = "16",
                    code = 16L,
                    version = "16.16",
                    isTorrent = false,
                    includeTorrentField = false,
                    sourceId = 954848840440936266L,
                    sourceName = "AniZone",
                    sourceLang = "all",
                    homeUrl = "https://anizone.to",
                ),
                buildAnimeExtPb(
                    name = "TorrentExt",
                    pkg = "eu.kanade.tachiyomi.animeextension.all.torrent",
                    lib = "16",
                    code = 1L,
                    version = "16.1",
                    isTorrent = true,
                    includeTorrentField = true,
                    sourceId = 999L,
                    sourceName = "TorrentExt",
                    sourceLang = "all",
                    homeUrl = "https://example.org",
                ),
            ),
        )

        val listWithoutTorrent = buildExtensionListPb(
            listOf(
                buildAnimeExtPb(
                    name = "AniZone",
                    pkg = "eu.kanade.tachiyomi.animeextension.all.anizone",
                    lib = "16",
                    code = 16L,
                    version = "16.16",
                    isTorrent = false,
                    includeTorrentField = false,
                    sourceId = 954848840440936266L,
                    sourceName = "AniZone",
                    sourceLang = "all",
                    homeUrl = "https://anizone.to",
                ),
            ),
        )

        val decodedWithTorrent = protoBuf.decodeExtensionList(listWithTorrent)
        decodedWithTorrent.extensions shouldHaveSize 2
        decodedWithTorrent.extensions[0].sources shouldHaveSize 1
        decodedWithTorrent.extensions[1].sources shouldHaveSize 1

        val decodedWithoutTorrent = protoBuf.decodeExtensionList(listWithoutTorrent)
        decodedWithoutTorrent.extensions shouldHaveSize 1
        decodedWithoutTorrent.extensions[0].sources shouldHaveSize 1
    }

    @Test
    fun `decodes Mihon v2 index pb without regression`() {
        val sourceBytes = concat(
            fieldVarint(1, 777L),
            fieldString(2, "Comix"),
            fieldString(3, "en"),
            fieldString(4, "https://comix.to"),
        )
        val resBytes = concat(
            fieldString(1, "https://example.com/comix.apk"),
            fieldString(2, "https://example.com/comix.png"),
        )
        val extBytes = concat(
            fieldString(1, "Comix"),
            fieldString(2, "eu.kanade.tachiyomi.extension.en.comix"),
            fieldBytes(3, resBytes),
            fieldString(4, "1.6"),
            fieldVarint(5, 106063L),
            fieldString(6, "1.6.63"),
            fieldVarint(7, 1L),
            fieldBytes(8, sourceBytes),
        )
        val storeBytes = buildStoreEnvelopePb(
            signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            extensionListBytes = fieldBytes(1, extBytes),
        )

        val decoded = protoBuf.decodeExtensionStore(storeBytes)
        decoded.extensionList!!.extensions shouldHaveSize 1
        decoded.extensionList!!.extensions[0].sources shouldHaveSize 1
        decoded.extensionList!!.extensions[0].sources[0].name shouldBe "Comix"
    }

    @Test
    fun `decodes Aniyomi v2 index json with isTorrent and string numbers`() {
        val rawJson = """
            {
              "name": "Yūzōnō",
              "badgeLabel": "Yū",
              "signingKey": "CBEC121AA82EBB02AAA73806992E0368A97D47B5451ED6524816D03084C45905",
              "contact": {
                "website": "https://yuzono.github.io",
                "discord": "https://discord.gg/85MZhUX688"
              },
              "extensionList": {
                "extensions": [
                  {
                    "name": "AnimeOnsen",
                    "packageName": "eu.kanade.tachiyomi.animeextension.all.animeonsen",
                    "resources": {
                      "apkUrl": "https://cdn.jsdelivr.net/gh/yuzono/anime-repo@repo/apk/aniyomi-all.animeonsen-v16.12.apk",
                      "iconUrl": "https://cdn.jsdelivr.net/gh/yuzono/anime-repo@repo/icon/eu.kanade.tachiyomi.animeextension.all.animeonsen.png"
                    },
                    "extensionLib": "16",
                    "versionCode": "12",
                    "versionName": "16.12",
                    "contentWarning": "CONTENT_WARNING_SAFE",
                    "isTorrent": true,
                    "sources": [
                      {
                        "id": "8542735178285060053",
                        "name": "AnimeOnsen",
                        "language": "all",
                        "homeUrl": "https://www.animeonsen.xyz"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val networkStore = json.decodeFromString<NetworkExtensionStore>(rawJson)
        val store = networkStore.toExtensionStore("https://github.com/yuzono/anime-repo/raw/repo/index.json")
        store.signingKey shouldBe "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"

        val available = networkStore.extensionList!!.toAvailableExtensions(store)
        available shouldHaveSize 1
        available[0].versionCode shouldBe 12L
        available[0].libVersion shouldBe 16.0
        available[0].contentWarning shouldBe ContentWarning.SAFE
        available[0].sources[0].id shouldBe 8542735178285060053L
    }

    @Test
    fun `parses legacy extension libVersion for anime and manga packages`() {
        val dummyStore = ExtensionStore(
            indexUrl = "https://example.com/repo.json",
            name = "Test",
            badgeLabel = "Test",
            signingKey = "abcd",
            contact = ExtensionStore.Contact(website = "https://example.com", discord = null),
            isLegacy = true,
            extensionListUrl = null,
        )

        val animeTwoPart = NetworkLegacyExtension(
            name = "Aniyomi: AnimeWorld India",
            pkg = "eu.kanade.tachiyomi.animeextension.all.animeworldindia",
            apk = "aniyomi-all.animeworldindia-v14.25.apk",
            lang = "all",
            code = 25,
            version = "14.25",
            nsfw = 0,
            sources = null,
        ).toAvailableExtension(dummyStore, "https://example.com")

        val animeThreePart = NetworkLegacyExtension(
            name = "Aniyomi: Test",
            pkg = "eu.kanade.tachiyomi.animeextension.en.test",
            apk = "aniyomi-en.test-v14.25.1.apk",
            lang = "en",
            code = 26,
            version = "14.25.1",
            nsfw = 0,
            sources = null,
        ).toAvailableExtension(dummyStore, "https://example.com")

        val mangaThreePart = NetworkLegacyExtension(
            name = "Tachiyomi: Comix",
            pkg = "eu.kanade.tachiyomi.extension.en.comix",
            apk = "tachiyomi-en.comix-v1.4.25.apk",
            lang = "en",
            code = 25,
            version = "1.4.25",
            nsfw = 0,
            sources = null,
        ).toAvailableExtension(dummyStore, "https://example.com")

        animeTwoPart.libVersion shouldBe 14.0
        animeThreePart.libVersion shouldBe 14.0
        mangaThreePart.libVersion shouldBe 1.4
    }

    private fun buildAnimeStorePb(signingKey: String, extensions: List<ByteArray>): ByteArray {
        return buildStoreEnvelopePb(
            signingKey = signingKey,
            extensionListBytes = buildExtensionListPb(extensions),
        )
    }

    private fun buildStoreEnvelopePb(signingKey: String, extensionListBytes: ByteArray): ByteArray {
        val contactBytes = concat(
            fieldString(1, "https://yuzono.github.io"),
            fieldString(2, "https://discord.gg/85MZhUX688"),
        )
        return concat(
            fieldString(1, "Yūzōnō"),
            fieldString(2, "Yū"),
            fieldString(3, signingKey),
            fieldBytes(4, contactBytes),
            fieldBytes(101, extensionListBytes),
        )
    }

    private fun buildExtensionListPb(extensions: List<ByteArray>): ByteArray {
        return concat(*extensions.map { fieldBytes(1, it) }.toTypedArray())
    }

    private fun buildAnimeExtPb(
        name: String,
        pkg: String,
        lib: String,
        code: Long,
        version: String,
        isTorrent: Boolean,
        includeTorrentField: Boolean,
        sourceId: Long,
        sourceName: String,
        sourceLang: String,
        homeUrl: String,
    ): ByteArray {
        val resBytes = concat(
            fieldString(1, "https://example.com/$pkg.apk"),
            fieldString(2, "https://example.com/$pkg.png"),
        )
        val sourceBytes = concat(
            fieldVarint(1, sourceId),
            fieldString(2, sourceName),
            fieldString(3, sourceLang),
            fieldString(4, homeUrl),
        )
        return concat(
            fieldString(1, name),
            fieldString(2, pkg),
            fieldBytes(3, resBytes),
            fieldString(4, lib),
            fieldVarint(5, code),
            fieldString(6, version),
            fieldVarint(7, 1L),
            if (includeTorrentField) fieldVarint(8, if (isTorrent) 1L else 0L) else ByteArray(0),
            fieldBytes(9, sourceBytes),
        )
    }

    private fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while ((v and -128L) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
        return out.toByteArray()
    }

    private fun fieldVarint(fieldNumber: Int, value: Long): ByteArray {
        return concat(encodeVarint((fieldNumber shl 3).toLong()), encodeVarint(value))
    }

    private fun fieldBytes(fieldNumber: Int, value: ByteArray): ByteArray {
        return concat(encodeVarint(((fieldNumber shl 3) or 2).toLong()), encodeVarint(value.size.toLong()), value)
    }

    private fun fieldString(fieldNumber: Int, value: String): ByteArray {
        return fieldBytes(fieldNumber, value.encodeToByteArray())
    }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            out.write(chunk)
        }
        return out.toByteArray()
    }
}
