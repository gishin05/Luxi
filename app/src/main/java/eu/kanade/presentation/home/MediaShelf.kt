package eu.kanade.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.model.MediaType
import tachiyomi.presentation.core.components.material.padding

@Composable
fun MediaShelf(
    mediaType: MediaType,
    items: List<Manga>,
    isLoading: Boolean,
    hasPinnedSources: Boolean = true,
    onViewMoreClick: () -> Unit,
    onItemClick: (Manga) -> Unit,
    onExploreExtensionsClick: () -> Unit,
    onRetryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small),
    ) {
        // Shelf Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = mediaType.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = mediaType.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading && items.isNotEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
        }

        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

        when {
            isLoading && items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items.isEmpty() -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (hasPinnedSources) {
                                "No ${mediaType.title} entries loaded"
                            } else {
                                "No pinned ${mediaType.title} extensions"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (hasPinnedSources && onRetryClick != null) {
                            OutlinedButton(onClick = onRetryClick) {
                                Text(text = "Retry")
                            }
                        } else {
                            OutlinedButton(onClick = onExploreExtensionsClick) {
                                Text(text = "Pin ${mediaType.title} Sources")
                            }
                        }
                    }
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
                        items = items,
                        key = { it.id },
                        contentType = { "shelf_item" },
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
                }
            }
        }
    }
}
