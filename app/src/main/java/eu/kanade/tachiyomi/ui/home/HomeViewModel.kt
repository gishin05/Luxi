package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.MediaType
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMediaTypeResolver
import java.util.concurrent.ConcurrentHashMap

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class HomeViewModel(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var isScreenActive = true
    private var pendingReloadOnResume = false
    private val fetchSemaphore = Semaphore(2)
    private val sourceItemsCache = ConcurrentHashMap<Long, Pair<String, List<Manga>>>()

    private var latestSources: List<Source> = emptyList()
    private var latestPinned: Set<String> = emptySet()
    private var latestDisabled: Set<String> = emptySet()
    private var latestEnabledLangs: Set<String> = emptySet()
    private var latestContentRating: String = SourcePreferences.CONTENT_RATING_SUGGESTIVE

    private data class HomeConfig(
        val sources: List<Source>,
        val pinnedSources: Set<String>,
        val disabledSources: Set<String>,
        val enabledLangs: Set<String>,
        val contentRating: String,
    )

    init {
        viewModelScope.launch {
            combine(
                sourceManager.sources,
                sourcePreferences.pinnedSources.changes(),
                sourcePreferences.disabledSources.changes(),
                sourcePreferences.enabledLanguages.changes(),
                sourcePreferences.contentRating.changes(),
            ) { sources, pinnedSources, disabledSources, enabledLangs, contentRating ->
                HomeConfig(sources, pinnedSources, disabledSources, enabledLangs, contentRating)
            }.collectLatest { config ->
                latestSources = config.sources
                latestPinned = config.pinnedSources
                latestDisabled = config.disabledSources
                latestEnabledLangs = config.enabledLangs
                latestContentRating = config.contentRating
                if (isScreenActive) {
                    scheduleShelfLoad(isRefresh = false)
                } else {
                    pendingReloadOnResume = true
                }
            }
        }
    }

    fun setScreenActive(active: Boolean) {
        if (isScreenActive == active) return
        isScreenActive = active
        if (!active) {
            if (loadJob?.isActive == true || _state.value.isLoading) {
                pendingReloadOnResume = true
            }
            loadJob?.cancel()
            loadJob = null
            _state.update {
                it.copy(
                    comixLoading = false,
                    animeLoading = false,
                    moviesLoading = false,
                    novelsLoading = false,
                    isRefreshing = false,
                )
            }
        } else if (pendingReloadOnResume && latestSources.isNotEmpty()) {
            scheduleShelfLoad(isRefresh = false)
        }
    }

    fun refresh() {
        sourceItemsCache.clear()
        scheduleShelfLoad(isRefresh = true)
    }

    private fun scheduleShelfLoad(isRefresh: Boolean) {
        loadJob?.cancel()
        pendingReloadOnResume = false
        loadJob = viewModelScope.launch {
            if (isRefresh) {
                _state.update { it.copy(isRefreshing = true) }
            }
            try {
                loadAllShelves(
                    sources = latestSources,
                    pinnedSources = latestPinned,
                    disabledSources = latestDisabled,
                    enabledLangs = latestEnabledLangs,
                    contentRating = latestContentRating,
                    forceFetch = isRefresh,
                )
            } finally {
                if (isRefresh) {
                    _state.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private suspend fun loadAllShelves(
        sources: List<Source>,
        pinnedSources: Set<String>,
        disabledSources: Set<String>,
        enabledLangs: Set<String>,
        contentRating: String,
        forceFetch: Boolean,
    ) {
        val validSources = sources
            .filterIsInstance<CatalogueSource>()
            .filter { src ->
                val isPinned = "${src.id}" in pinnedSources
                val langAllowed = src.lang in enabledLangs || src.lang == "all" || src.lang.isEmpty()
                val notDisabled = "${src.id}" !in disabledSources
                isPinned && langAllowed && notDisabled
            }
            .sortedByDescending { it.lang == "en" }
            .distinctBy { it.name }

        val sourcesByType = MediaType.entries.associateWith { type ->
            validSources.filter { type in SourceMediaTypeResolver.resolveAll(it) }
        }

        val stateMutex = Mutex()
        val completedItems = HashMap<Long, List<Manga>>()
        val pendingIdsByType = MediaType.entries.associateWith { type ->
            sourcesByType[type].orEmpty().map { it.id }.toMutableSet()
        }
        val toFetch = mutableListOf<CatalogueSource>()

        validSources.forEach { src ->
            val cached = if (!forceFetch) sourceItemsCache[src.id] else null
            if (cached != null && cached.first == contentRating) {
                completedItems[src.id] = cached.second
                SourceMediaTypeResolver.resolveAll(src).forEach { type ->
                    pendingIdsByType[type]?.remove(src.id)
                }
            } else {
                toFetch.add(src)
            }
        }

        _state.update { current ->
            var next = current
            MediaType.entries.forEach { type ->
                val typeSources = sourcesByType[type].orEmpty()
                val allowedIds = typeSources.mapTo(HashSet()) { it.id }
                val cachedMerged = interleave(typeSources.mapNotNull { completedItems[it.id] }).take(20)
                val items = if (cachedMerged.isNotEmpty() ||
                    toFetch.none { type in SourceMediaTypeResolver.resolveAll(it) }
                ) {
                    cachedMerged
                } else {
                    current.listFor(type).filter { it.source in allowedIds }
                }
                val stillLoading = pendingIdsByType[type]?.isNotEmpty() == true
                next = next.withShelf(type, items, stillLoading, hasPinned = typeSources.isNotEmpty())
            }
            next
        }

        if (toFetch.isEmpty()) return

        coroutineScope {
            toFetch.forEach { source ->
                launch(Dispatchers.IO) {
                    val items = fetchSemaphore.withPermit {
                        fetchSourceItems(source, contentRating)
                    }
                    if (items.isNotEmpty()) {
                        sourceItemsCache[source.id] = contentRating to items
                    }
                    val affectedTypes = SourceMediaTypeResolver.resolveAll(source)

                    stateMutex.withLock {
                        if (items.isNotEmpty()) {
                            completedItems[source.id] = items
                        } else {
                            completedItems.remove(source.id)
                        }
                        affectedTypes.forEach { type ->
                            pendingIdsByType[type]?.remove(source.id)
                        }

                        _state.update { current ->
                            var next = current
                            affectedTypes.forEach { type ->
                                val typeSources = sourcesByType[type].orEmpty()
                                val orderedResults = typeSources.mapNotNull { completedItems[it.id] }
                                val merged = interleave(orderedResults).take(20)
                                val stillLoading = pendingIdsByType[type]?.isNotEmpty() == true
                                next = next.withShelf(type, merged, stillLoading, hasPinned = typeSources.isNotEmpty())
                            }
                            next
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchSourceItems(source: CatalogueSource, contentRating: String): List<Manga> {
        return try {
            val mangasPage = source.getPopularManga(1)
            val domainMangas = mangasPage.mangas
                .asSequence()
                .map { it.toDomainManga(source.id) }
                .filter { SourcePreferences.isMangaAllowedByContentRating(it.genre, contentRating) }
                .take(20)
                .toList()
            networkToLocalManga(domainMangas)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun interleave(lists: List<List<Manga>>): List<Manga> {
        if (lists.isEmpty()) return emptyList()
        val result = mutableListOf<Manga>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (list in lists) {
                if (i < list.size) {
                    result.add(list[i])
                }
            }
        }
        return result.distinctBy { it.id }
    }

    @Immutable
    data class State(
        val comixLoading: Boolean = true,
        val animeLoading: Boolean = true,
        val moviesLoading: Boolean = true,
        val novelsLoading: Boolean = true,
        val comixHasPinned: Boolean = true,
        val animeHasPinned: Boolean = true,
        val moviesHasPinned: Boolean = true,
        val novelsHasPinned: Boolean = true,
        val isRefreshing: Boolean = false,
        val comixList: List<Manga> = emptyList(),
        val animeList: List<Manga> = emptyList(),
        val moviesList: List<Manga> = emptyList(),
        val novelsList: List<Manga> = emptyList(),
    ) {
        val isLoading: Boolean
            get() = comixLoading || animeLoading || moviesLoading || novelsLoading

        fun listFor(type: MediaType): List<Manga> = when (type) {
            MediaType.COMIX -> comixList
            MediaType.ANIME -> animeList
            MediaType.MOVIES -> moviesList
            MediaType.NOVELS -> novelsList
        }

        fun withShelf(type: MediaType, items: List<Manga>, loading: Boolean, hasPinned: Boolean): State = when (type) {
            MediaType.COMIX -> copy(comixList = items, comixLoading = loading, comixHasPinned = hasPinned)
            MediaType.ANIME -> copy(animeList = items, animeLoading = loading, animeHasPinned = hasPinned)
            MediaType.MOVIES -> copy(moviesList = items, moviesLoading = loading, moviesHasPinned = hasPinned)
            MediaType.NOVELS -> copy(novelsList = items, novelsLoading = loading, novelsHasPinned = hasPinned)
        }
    }
}
