package eu.kanade.tachiyomi.source.novel.lnreader

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

class LNReaderPluginRepository(
    private val context: Context,
    private val networkHelper: NetworkHelper,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val storageDir = File(context.filesDir, "lnreader").apply { mkdirs() }
    private val pluginsDir = File(storageDir, "plugins").apply { mkdirs() }
    private val installedDir = File(storageDir, "installed").apply { mkdirs() }
    private val manifestFile = File(storageDir, "manifest.json")

    fun getInstalledPlugins(): List<LNPluginMeta> {
        val list = mutableListOf<LNPluginMeta>()
        installedDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
            try {
                list.add(json.decodeFromString<LNPluginMeta>(f.readText()))
            } catch (_: Exception) {}
        }
        return list
    }

    fun saveInstalledPlugin(meta: LNPluginMeta) {
        try {
            val f = File(installedDir, "${meta.id}.json")
            f.writeText(json.encodeToString(meta))
        } catch (_: Exception) {}
    }

    fun removeInstalledPlugin(pluginId: String) {
        try {
            File(installedDir, "$pluginId.json").delete()
            File(pluginsDir, "$pluginId.js").delete()
        } catch (_: Exception) {}
    }

    suspend fun getAvailablePlugins(forceRefresh: Boolean = false): List<LNPluginMeta> = withContext(Dispatchers.IO) {
        if (!forceRefresh && manifestFile.exists() && manifestFile.length() > 0) {
            try {
                return@withContext json.decodeFromString<List<LNPluginMeta>>(manifestFile.readText())
            } catch (e: Exception) {
                // Ignore and re-fetch
            }
        }

        try {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .build()
            val response = networkHelper.client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                manifestFile.writeText(body)
                return@withContext json.decodeFromString<List<LNPluginMeta>>(body)
            }
        } catch (e: Exception) {
            // Network failure: fallback to cached or presets
        }

        if (manifestFile.exists() && manifestFile.length() > 0) {
            try {
                return@withContext json.decodeFromString<List<LNPluginMeta>>(manifestFile.readText())
            } catch (e: Exception) {
                // Ignore
            }
        }

        PRESET_PLUGINS
    }

    suspend fun getPluginScript(plugin: LNPluginMeta): String = withContext(Dispatchers.IO) {
        val scriptFile = File(pluginsDir, "${plugin.id}.js")
        if (scriptFile.exists() && scriptFile.length() > 0) {
            return@withContext scriptFile.readText()
        }

        try {
            val assetStream = context.assets.open("lnreader/plugins/${plugin.id}.js")
            val assetCode = assetStream.bufferedReader().use { it.readText() }
            if (assetCode.isNotBlank()) {
                scriptFile.writeText(assetCode)
                return@withContext assetCode
            }
        } catch (_: Exception) {
            // Not in assets, fallback to network
        }

        val encodedUrl = plugin.url
            .replace("[", "%5B")
            .replace("]", "%5D")

        val request = Request.Builder()
            .url(encodedUrl)
            .build()

        val response = networkHelper.client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to download LNReader plugin ${plugin.name} (HTTP ${response.code})")
        }

        val code = response.body.string()
        scriptFile.writeText(code)
        code
    }

    companion object {
        private const val BASE_REPO =
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0"
        const val MANIFEST_URL = "$BASE_REPO/.dist/plugins.min.json"

        val PRESET_PLUGINS = listOf(
            LNPluginMeta(
                id = "royalroad",
                name = "Royal Road",
                site = "https://www.royalroad.com/",
                lang = "English",
                version = "2.3.4",
                url = "$BASE_REPO/.js/src/plugins/english/royalroad.js",
                iconUrl = "$BASE_REPO/public/static/src/en/royalroad/icon.png",
            ),
            LNPluginMeta(
                id = "allnovel",
                name = "AllNovel",
                site = "https://novelfull.com/",
                lang = "English",
                version = "2.3.6",
                url = "$BASE_REPO/.js/src/plugins/english/AllNovel[readnovelfull].js",
                iconUrl = "$BASE_REPO/public/static/multisrc/readnovelfull/allnovel/icon.png",
            ),
            LNPluginMeta(
                id = "boxnovel",
                name = "BoxNovel",
                site = "https://novelnice.com/",
                lang = "English",
                version = "2.4.0",
                url = "$BASE_REPO/.js/src/plugins/english/BoxNovel[madara].js",
                iconUrl = "$BASE_REPO/public/static/multisrc/madara/boxnovel/icon.png",
            ),
            LNPluginMeta(
                id = "1stkissnovel",
                name = "FirstKissNovel",
                site = "https://1stkissnovel.org/",
                lang = "English",
                version = "2.4.0",
                url = "$BASE_REPO/.js/src/plugins/english/FirstKissNovel[madara].js",
                iconUrl = "$BASE_REPO/public/static/multisrc/madara/1stkissnovel/icon.png",
            ),
        )
    }
}
