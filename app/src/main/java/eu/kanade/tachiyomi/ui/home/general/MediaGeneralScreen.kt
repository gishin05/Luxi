package eu.kanade.tachiyomi.ui.home.general

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.model.MediaType
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding

class MediaGeneralScreen(
    private val mediaTypeId: String,
) : Screen() {

    constructor(mediaType: MediaType) : this(mediaType.id)

    override val key: ScreenKey = "media_general_$mediaTypeId"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<MediaGeneralViewModel, MediaGeneralViewModel.Factory>(key = key) {
            create(mediaTypeId = mediaTypeId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        DisposableEffect(mediaTypeId) {
            viewModel.setScreenActive(true)
            onDispose { viewModel.setScreenActive(false) }
        }

        var isSearchActive by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                Column {
                    if (isSearchActive) {
                        SearchToolbar(
                            searchQuery = state.query,
                            onChangeSearchQuery = { viewModel.search(it.orEmpty()) },
                            onSearch = { viewModel.search(it) },
                            onClickCloseSearch = {
                                isSearchActive = false
                                viewModel.search("")
                            },
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = state.mediaType.title,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = state.mediaType.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { navigator.pop() }) {
                                    Icon(
                                        imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.Search,
                                        contentDescription = "Search",
                                    )
                                }
                            },
                        )
                    }

                    // Chips Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == MediaGeneralViewModel.Listing.Popular,
                            onClick = { viewModel.setListing(MediaGeneralViewModel.Listing.Popular) },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.RoundedFilled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = { Text("Popular") },
                        )
                        FilterChip(
                            selected = state.listing == MediaGeneralViewModel.Listing.Latest,
                            onClick = { viewModel.setListing(MediaGeneralViewModel.Listing.Latest) },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Verified,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = { Text("Latest") },
                        )

                        // Source Filter Chips
                        FilterChip(
                            selected = state.selectedSourceId == null,
                            onClick = { viewModel.setSelectedSource(null) },
                            label = { Text("All Sources") },
                        )

                        state.sources.forEach { source ->
                            FilterChip(
                                selected = state.selectedSourceId == source.id,
                                onClick = { viewModel.setSelectedSource(source.id) },
                                label = { Text(source.name) },
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
        ) { paddingValues ->
            if (state.selectedSourceId != null) {
                // Single source selected: show full vertical grid
                if (state.isLoading && state.selectedSourceItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (state.selectedSourceItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No entries found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(
                            start = MaterialTheme.padding.small,
                            end = MaterialTheme.padding.small,
                            top = paddingValues.calculateTopPadding() + MaterialTheme.padding.small,
                            bottom = paddingValues.calculateBottomPadding() + MaterialTheme.padding.small,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = state.selectedSourceItems,
                            key = { it.id },
                            contentType = { "drawer_grid_item" },
                        ) { manga ->
                            val coverData = remember(
                                manga.id,
                                manga.thumbnailUrl,
                                manga.coverLastModified,
                                manga.favorite,
                            ) {
                                manga.asMangaCover()
                            }
                            MangaComfortableGridItem(
                                title = manga.title,
                                titleMaxLines = 2,
                                coverData = coverData,
                                coverAlpha = if (manga.favorite) {
                                    CommonMangaItemDefaults.BrowseFavoriteCoverAlpha
                                } else {
                                    1f
                                },
                                onClick = { navigator.push(MangaScreen(manga.id, true)) },
                                onLongClick = { navigator.push(MangaScreen(manga.id, true)) },
                            )
                        }

                        if (state.hasNextPage && state.selectedSourceItems.isNotEmpty()) {
                            item(
                                key = "loading-grid",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                LaunchedEffect(state.selectedSourcePage) {
                                    viewModel.loadMoreForSelectedSource()
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // All sources selected: show drawer for each extension/source
                val visibleDrawers = if (state.query.isNotBlank()) {
                    state.drawers.filter { it.isLoading || it.items.isNotEmpty() }
                } else {
                    state.drawers
                }

                if (state.isLoading && visibleDrawers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (visibleDrawers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.query.isNotBlank()) {
                                "No entries found for \"${state.query}\""
                            } else {
                                "No extensions or sources found"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding() + MaterialTheme.padding.small,
                            bottom = paddingValues.calculateBottomPadding() + MaterialTheme.padding.small,
                        ),
                    ) {
                        items(
                            items = visibleDrawers,
                            key = { it.source.id },
                            contentType = { "source_drawer" },
                        ) { drawer ->
                            SourceDrawerRow(
                                drawer = drawer,
                                onViewMoreClick = { navigator.push(BrowseSourceScreen(drawer.source.id)) },
                                onItemClick = { manga -> navigator.push(MangaScreen(manga.id, true)) },
                                onLoadMore = { viewModel.loadMoreForDrawer(drawer.source.id) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SourceDrawerRow(
        drawer: MediaGeneralViewModel.SourceDrawer,
        onViewMoreClick: () -> Unit,
        onItemClick: (Manga) -> Unit,
        onLoadMore: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.extraSmall),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    Text(
                        text = drawer.source.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (drawer.source.lang.isNotBlank() && drawer.source.lang != "all") {
                        Text(
                            text = "(${drawer.source.lang.uppercase()})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "View all >",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onViewMoreClick)
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

            when {
                drawer.isLoading && drawer.items.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                drawer.items.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                    ) {
                        Text(
                            text = "No entries available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp),
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        items(
                            items = drawer.items,
                            key = { it.id },
                            contentType = { "drawer_item" },
                        ) { manga ->
                            val coverData = remember(
                                manga.id,
                                manga.thumbnailUrl,
                                manga.coverLastModified,
                                manga.favorite,
                            ) {
                                manga.asMangaCover()
                            }
                            Box(modifier = Modifier.width(112.dp)) {
                                MangaComfortableGridItem(
                                    title = manga.title,
                                    titleMaxLines = 2,
                                    coverData = coverData,
                                    coverAlpha = if (manga.favorite) {
                                        CommonMangaItemDefaults.BrowseFavoriteCoverAlpha
                                    } else {
                                        1f
                                    },
                                    onClick = { onItemClick(manga) },
                                    onLongClick = { onItemClick(manga) },
                                )
                            }
                        }

                        if (drawer.hasNextPage && drawer.items.isNotEmpty()) {
                            item(key = "loading-${drawer.source.id}") {
                                LaunchedEffect(drawer.page) {
                                    onLoadMore()
                                }
                                Box(
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
