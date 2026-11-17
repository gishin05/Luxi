package eu.kanade.tachiyomi.source.novel

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.novel.builtin.BakaTsukiNovelSource
import eu.kanade.tachiyomi.source.novel.lnreader.LNPluginMeta
import eu.kanade.tachiyomi.source.novel.lnreader.LNReaderEngine
import eu.kanade.tachiyomi.source.novel.lnreader.LNReaderPluginRepository
import eu.kanade.tachiyomi.source.novel.lnreader.LNReaderSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class LNReaderSourceManager(
    private val context: Context,
    private val networkHelper: NetworkHelper,
) {

    val repository = LNReaderPluginRepository(context, networkHelper)
    val engine = LNReaderEngine(context, networkHelper)

    private val bakaTsukiSource = BakaTsukiNovelSource(networkHelper)

    private val activeSources = ConcurrentHashMap<Long, NovelSource>()
    private val _sourcesFlow = MutableStateFlow<List<NovelSource>>(emptyList())
    val sourcesFlow = _sourcesFlow.asStateFlow()

    init {
        // Register default native online source
        activeSources[bakaTsukiSource.id] = bakaTsukiSource

        // Register default preset LNReader sources
        LNReaderPluginRepository.PRESET_PLUGINS.forEach { meta ->
            val src = LNReaderSource(meta, repository, engine, networkHelper)
            activeSources[src.id] = src
        }

        // Register any user-installed plugins from storage
        repository.getInstalledPlugins().forEach { meta ->
            val src = LNReaderSource(meta, repository, engine, networkHelper)
            activeSources[src.id] = src
        }

        updateFlow()
    }

    fun getAllSources(): List<NovelSource> {
        return activeSources.values.toList()
    }

    suspend fun getAvailableOnlinePlugins(forceRefresh: Boolean = false): List<LNPluginMeta> {
        return repository.getAvailablePlugins(forceRefresh)
    }

    fun enablePlugin(meta: LNPluginMeta): LNReaderSource {
        val src = LNReaderSource(meta, repository, engine, networkHelper)
        activeSources[src.id] = src
        repository.saveInstalledPlugin(meta)
        updateFlow()
        return src
    }

    fun disablePlugin(sourceId: Long) {
        val src = activeSources.remove(sourceId)
        if (src is LNReaderSource) {
            repository.removeInstalledPlugin(src.plugin.id)
        }
        updateFlow()
    }

    private fun updateFlow() {
        _sourcesFlow.value = activeSources.values.toList()
    }
}
