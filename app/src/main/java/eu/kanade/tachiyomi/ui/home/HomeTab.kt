package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.home.MediaShelf
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.home.general.MediaGeneralScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
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
        var showContentFilterDialog by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { showContentFilterDialog = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.FilterList,
                                contentDescription = stringResource(MR.strings.pref_content_rating_title),
                            )
                        }
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
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

        if (showContentFilterDialog) {
            ContentFilterDialog(
                scope = scope,
                onDismissRequest = { showContentFilterDialog = false },
            )
        }
    }
}

@Composable
private fun ContentFilterDialog(
    scope: CoroutineScope,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val sourcePreferences = remember { context.appGraph.sourcePreferences }
    val sourceManager = remember { context.appGraph.sourceManager }
    val currentRating by sourcePreferences.contentRating.asState(scope)

    val ratingEntries = listOf(
        SourcePreferences.CONTENT_RATING_SAFE to stringResource(MR.strings.pref_content_rating_safe),
        SourcePreferences.CONTENT_RATING_SUGGESTIVE to stringResource(MR.strings.pref_content_rating_suggestive),
        SourcePreferences.CONTENT_RATING_EROTICA to stringResource(MR.strings.pref_content_rating_erotica),
        SourcePreferences.CONTENT_RATING_PORNOGRAPHIC to stringResource(
            MR.strings.pref_content_rating_pornographic,
        ),
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.pref_content_rating_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(MR.strings.pref_content_rating_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                )
                ratingEntries.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sourcePreferences.contentRating.set(value)
                                sourcePreferences.enabledContentWarnings.set(
                                    SourcePreferences.contentWarningsForRating(value),
                                )
                                scope.launch(Dispatchers.IO) {
                                    val sources = sourceManager.getAll()
                                    SourcePreferences.syncExtensionContentRating(context, sources, value)
                                }
                                onDismissRequest()
                            }
                            .padding(vertical = MaterialTheme.padding.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentRating == value,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = MaterialTheme.padding.small),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
