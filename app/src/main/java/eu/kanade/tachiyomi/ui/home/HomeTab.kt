package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.home.MediaShelf
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.home.general.MediaGeneralScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.roundedfilled.Home
import tachiyomi.domain.source.model.MediaType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

data object HomeTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val icon = if (isSelected) {
                rememberVectorPainter(MaterialSymbols.RoundedFilled.Home)
            } else {
                rememberVectorPainter(MaterialSymbols.Rounded.Home)
            }
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_home),
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val viewModel = metroViewModel<HomeViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        DisposableEffect(Unit) {
            viewModel.setScreenActive(true)
            onDispose { viewModel.setScreenActive(false) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Luxi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    actions = {
                        IconButton(onClick = { navigator.push(GlobalSearchScreen("")) }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Search,
                                contentDescription = "Search",
                            )
                        }
                        IconButton(onClick = { navigator.push(SettingsScreen(SettingsScreen.Destination.Home)) }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            PullRefresh(
                refreshing = state.isRefreshing,
                enabled = true,
                onRefresh = viewModel::refresh,
                indicatorPadding = paddingValues,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = MediaType.COMIX.id, contentType = "shelf") {
                        MediaShelf(
                            mediaType = MediaType.COMIX,
                            items = state.comixList,
                            isLoading = state.comixLoading,
                            hasPinnedSources = state.comixHasPinned,
                            onViewMoreClick = { navigator.push(MediaGeneralScreen(MediaType.COMIX)) },
                            onItemClick = { manga -> navigator.push(MangaScreen(manga.id, true)) },
                            onExploreExtensionsClick = {
                                scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browse(toExtensions = false)) }
                            },
                            onRetryClick = viewModel::refresh,
                        )
                    }

                    item(key = MediaType.ANIME.id, contentType = "shelf") {
                        MediaShelf(
                            mediaType = MediaType.ANIME,
                            items = state.animeList,
                            isLoading = state.animeLoading,
                            hasPinnedSources = state.animeHasPinned,
                            onViewMoreClick = { navigator.push(MediaGeneralScreen(MediaType.ANIME)) },
                            onItemClick = { manga -> navigator.push(MangaScreen(manga.id, true)) },
                            onExploreExtensionsClick = {
                                scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browse(toExtensions = false)) }
                            },
                            onRetryClick = viewModel::refresh,
                        )
                    }

                    item(key = MediaType.MOVIES.id, contentType = "shelf") {
                        MediaShelf(
                            mediaType = MediaType.MOVIES,
                            items = state.moviesList,
                            isLoading = state.moviesLoading,
                            hasPinnedSources = state.moviesHasPinned,
                            onViewMoreClick = { navigator.push(MediaGeneralScreen(MediaType.MOVIES)) },
                            onItemClick = { manga -> navigator.push(MangaScreen(manga.id, true)) },
                            onExploreExtensionsClick = {
                                scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browse(toExtensions = false)) }
                            },
                            onRetryClick = viewModel::refresh,
                        )
                    }

                    item(key = MediaType.NOVELS.id, contentType = "shelf") {
                        MediaShelf(
                            mediaType = MediaType.NOVELS,
                            items = state.novelsList,
                            isLoading = state.novelsLoading,
                            hasPinnedSources = state.novelsHasPinned,
                            onViewMoreClick = { navigator.push(MediaGeneralScreen(MediaType.NOVELS)) },
                            onItemClick = { manga -> navigator.push(MangaScreen(manga.id, true)) },
                            onExploreExtensionsClick = {
                                scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browse(toExtensions = false)) }
                            },
                            onRetryClick = viewModel::refresh,
                        )
                    }

                    item(key = "bottom_spacer", contentType = "spacer") {
                        Spacer(modifier = Modifier.height(MaterialTheme.padding.large))
                    }
                }
            }
        }
    }
}
