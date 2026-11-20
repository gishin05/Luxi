package eu.kanade.tachiyomi.ui.home.general

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.ComixRequestGate
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.MediaType
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMediaTypeResolver
import java.util.concurrent.ConcurrentHashMap

@AssistedInject
class MediaGeneralViewModel(
    @Assisted private val mediaTypeId: String,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ViewModel() {

    val mediaType: MediaType = MediaType.fromId(mediaTypeId)

    private val _state = MutableStateFlow(State(mediaType = mediaType))
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var isScreenActive = true
    private var pendingReloadOnResume = false
    private val fetchSemaphore = Semaphore(2)

    private data class CacheKey(
        val listing: Listing,
        val query: String,
        val contentRating: String,
    )

    private data class CachedPage(
        val key: CacheKey,
        val items: List<Manga>,
        val hasNextPage: Boolean,
    )

    private val drawerCache = ConcurrentHashMap<Long, CachedPage>()

    private data class SourceConfig(
        val sources: List<eu.kanade.tachiyomi.source.Source>,
        val pinned: Set<String>,
        val disabled: Set<String>,
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
            ) { allSources, pinned, disabled, enabledLangs, rating ->
                SourceConfig(allSources, pinned, disabled, enabledLangs, rating)
            }.collectLatest { config ->
                val matching = config.sources
                    .filterIsInstance<CatalogueSource>()
                    .filter { SourceMediaTypeResolver.resolveAll(it).contains(mediaType) }
                    .filter { src ->
                        val isPinned = "${src.id}" in config.pinned
                        val langAllowed = src.lang in config.enabledLangs || src.lang == "all" || src.lang.isEmpty()
                        val notDisabled = "${src.id}" !in config.disabled
                        isPinned && langAllowed && notDisabled
                    }
                    .sortedByDescending { it.lang == "en" }
                    .distinctBy { it.name }
                val allowedIds = matching.mapTo(HashSet()) { it.id }
                _state.update { current ->
                    current.copy(
                        sources = matching,
                        selectedSourceId = current.selectedSourceId?.takeIf { it in allowedIds },
                    )
                }
                if (isScreenActive) {
                    loadContent()
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
            if (loadJob?.isActive == true || _state.value.isLoading || _state.value.drawers.any { it.isLoading }) {
                pendingReloadOnResume = true
            }
            loadJob?.cancel()
            loadJob = null
            _state.update { current ->
                current.copy(
                    isLoading = false,
                    drawers = current.drawers.map { it.copy(isLoading = false) },
                )
            }
        } else if ((
                pendingReloadOnResume || _state.value.drawers.any { it.items.isEmpty() } ||
                    (_state.value.selectedSourceId == null && _state.value.drawers.isEmpty())
                ) &&
            _state.value.sources.isNotEmpty()
        ) {
            loadContent()
        }
    }

    fun setListing(listing: Listing) {
        if (_state.value.listing != listing) {
            _state.update { it.copy(listing = listing) }
            loadContent()
        }
    }

    fun setSelectedSource(sourceId: Long?) {
        if (_state.value.selectedSourceId != sourceId) {
            _state.update { it.copy(selectedSourceId = sourceId) }
            loadContent()
        }
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        loadContent()
    }

    fun refresh() {
        drawerCache.clear()
        loadContent(forceFetch = true)
    }

    private fun loadContent(forceFetch: Boolean = false) {
        loadJob?.cancel()
        pendingReloadOnResume = false
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            val rating = sourcePreferences.contentRating.get()
            val key = CacheKey(current.listing, current.query, rating)
            val targetSources = if (current.selectedSourceId != null) {
                current.sources.filter { it.id == current.selectedSourceId }
            } else {
                current.sources
            }

            if (targetSources.isEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        drawers = emptyList(),
                        selectedSourceItems = emptyList(),
                    )
                }
                return@launch
            }

            if (current.selectedSourceId != null) {
                val singleSource = targetSources.first()
                val cached = if (!forceFetch) drawerCache[singleSource.id]?.takeIf { it.key == key } else null
                if (cached != null) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            selectedSourceItems = cached.items,
                            hasNextPage = cached.hasNextPage && cached.items.isNotEmpty(),
                            selectedSourcePage = 1,
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        isLoading = true,
                        selectedSourceItems = emptyList(),
                        hasNextPage = true,
                        selectedSourcePage = 1,
                    )
                }
                val (items, hasNextPage) = fetchSemaphore.withPermit {
                    fetchItemsForSource(singleSource, current.query, current.listing, page = 1)
                }
                if (items.isNotEmpty()) {
                    drawerCache[singleSource.id] = CachedPage(key, items, hasNextPage)
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        selectedSourceItems = items,
                        hasNextPage = hasNextPage && items.isNotEmpty(),
                        selectedSourcePage = 1,
                    )
                }
            } else {
                val initialDrawers = targetSources.map { src ->
                    val cached = if (!forceFetch) drawerCache[src.id]?.takeIf { it.key == key } else null
                    if (cached != null) {
                        SourceDrawer(
                            source = src,
                            items = cached.items,
                            isLoading = false,
                            hasNextPage = cached.hasNextPage && cached.items.isNotEmpty(),
                            page = 1,
                        )
                    } else {
                        SourceDrawer(
                            source = src,
                            isLoading = true,
                            hasNextPage = true,
                            page = 1,
                        )
                    }
                }
                _state.update {
                    it.copy(
                        isLoading = initialDrawers.any { drawer -> drawer.isLoading },
                        drawers = initialDrawers,
                    )
                }

                targetSources.forEach { source ->
                    val cached = if (!forceFetch) drawerCache[source.id]?.takeIf { it.key == key } else null
                    if (cached != null) return@forEach

                    launch(Dispatchers.IO) {
                        val (items, hasNextPage) = fetchSemaphore.withPermit {
                            fetchItemsForSource(source, current.query, current.listing, page = 1)
                        }
                        if (items.isNotEmpty()) {
                            drawerCache[source.id] = CachedPage(key, items, hasNextPage)
                        }
                        _state.update { state ->
                            val updatedDrawers = state.drawers.map { drawer ->
                                if (drawer.source.id == source.id) {
                                    drawer.copy(
                                        items = items,
                                        isLoading = false,
                                        hasNextPage = hasNextPage && items.isNotEmpty(),
                                        page = 1,
                                    )
                                } else {
                                    drawer
                                }
                            }
                            state.copy(
                                drawers = updatedDrawers,
                                isLoading = updatedDrawers.any { it.isLoading },
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadMoreForDrawer(sourceId: Long) {
        val currentDrawer = _state.value.drawers.find { it.source.id == sourceId } ?: return
        if (currentDrawer.isLoading || currentDrawer.isLoadingMore || !currentDrawer.hasNextPage) return

        _state.update { state ->
            val updated = state.drawers.map { drawer ->
                if (drawer.source.id == sourceId) {
                    drawer.copy(isLoadingMore = true)
                } else {
                    drawer
                }
            }
            state.copy(drawers = updated)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val nextPage = currentDrawer.page + 1
            val current = _state.value
            val (newItems, hasNextPage) = fetchItemsForSource(
                source = currentDrawer.source,
                query = current.query,
                listing = current.listing,
                page = nextPage,
            )

            _state.update { state ->
                val updated = state.drawers.map { drawer ->
                    if (drawer.source.id == sourceId) {
                        val existingIds = drawer.items.map { it.id }.toHashSet()
                        val distinctNewItems = newItems.filter { it.id !in existingIds }
                        drawer.copy(
                            items = drawer.items + distinctNewItems,
                            isLoadingMore = false,
                            hasNextPage = hasNextPage && newItems.isNotEmpty(),
                            page = nextPage,
                        )
                    } else {
                        drawer
                    }
                }
                state.copy(drawers = updated)
            }
        }
    }

    fun loadMoreForSelectedSource() {
        val current = _state.value
        val sourceId = current.selectedSourceId ?: return
        val source = current.sources.find { it.id == sourceId } ?: return
        if (current.isLoading || current.isLoadingMore || !current.hasNextPage) return

        _state.update { it.copy(isLoadingMore = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val nextPage = current.selectedSourcePage + 1
            val (newItems, hasNextPage) = fetchItemsForSource(
                source = source,
                query = current.query,
                listing = current.listing,
                page = nextPage,
            )

            _state.update { state ->
                val existingIds = state.selectedSourceItems.map { it.id }.toHashSet()
                val distinctNewItems = newItems.filter { it.id !in existingIds }
                state.copy(
                    selectedSourceItems = state.selectedSourceItems + distinctNewItems,
                    isLoadingMore = false,
                    hasNextPage = hasNextPage && newItems.isNotEmpty(),
                    selectedSourcePage = nextPage,
                )
            }
        }
    }

    private suspend fun fetchItemsForSource(
        source: CatalogueSource,
        query: String,
        listing: Listing,
        page: Int = 1,
    ): Pair<List<Manga>, Boolean> {
        return try {
            val fetchPage: suspend () -> eu.kanade.tachiyomi.source.model.MangasPage = {
                when {
                    query.isNotBlank() -> source.getSearchManga(page, query, source.getFilterList())
                    listing == Listing.Popular -> source.getPopularManga(page)
                    else -> if (source.supportsLatest) {
                        try {
                            source.getLatestUpdates(page)
                        } catch (e: Exception) {
                            source.getPopularManga(page)
                        }
                    } else {
                        source.getPopularManga(page)
                    }
                }
            }
            val mangasPage = if (ComixRequestGate.isComix(source)) {
                ComixRequestGate.withPacing { fetchPage() }
            } else {
                fetchPage()
            }

            val rating = sourcePreferences.contentRating.get()
            val items = mangasPage.mangas
                .map { it.toDomainManga(source.id) }
                .filter { SourcePreferences.isMangaAllowedByContentRating(it.genre, rating) }
                .let { networkToLocalManga(it) }
            Pair(items, mangasPage.hasNextPage)
        } catch (e: Exception) {
            Pair(emptyList(), false)
        }
    }

    enum class Listing {
        Popular,
        Latest,
    }

    @Immutable
    data class SourceDrawer(
        val source: CatalogueSource,
        val items: List<Manga> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasNextPage: Boolean = true,
        val page: Int = 1,
    )

    @Immutable
    data class State(
        val mediaType: MediaType,
        val sources: List<CatalogueSource> = emptyList(),
        val selectedSourceId: Long? = null,
        val listing: Listing = Listing.Latest,
        val query: String = "",
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val hasNextPage: Boolean = true,
        val selectedSourcePage: Int = 1,
        val drawers: List<SourceDrawer> = emptyList(),
        val selectedSourceItems: List<Manga> = emptyList(),
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mediaTypeId: String): MediaGeneralViewModel
    }
}
