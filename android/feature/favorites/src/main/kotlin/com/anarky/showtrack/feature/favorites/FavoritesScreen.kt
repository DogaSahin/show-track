package com.anarky.showtrack.feature.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.CountdownBadge
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCard
import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen`/`DiscoverScreen` use.
 *
 * [onEntryClick] hands the whole [LibraryEntry] to the caller rather than a raw id, exactly
 * `LibraryScreen`'s own parameter: a [LibraryEntry]'s own id identifies the user's library ROW,
 * while the detail screen's route needs `entry.media.id` — two different primary keys, and the
 * translation belongs at the module's graph boundary (`FavoritesNavigation.kt`), not here.
 */
@Composable
fun FavoritesScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FavoritesScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onEntryClick = onEntryClick,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`/`DiscoverScreen`'s pattern.
 *
 * No status tabs, no sort control — decision D-H: Favorites' layout is a deliberately simpler
 * duplicate of Library's, not a shared component, because the two screens legitimately diverge
 * here and will keep diverging.
 */
@Composable
internal fun FavoritesScreen(
    state: FavoritesUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.favorites_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            when (state) {
                is FavoritesUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                is FavoritesUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.favorites_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is FavoritesUiState.Success ->
                    if (state.entries.isEmpty()) {
                        EmptyState(
                            message = stringResource(R.string.favorites_empty_message),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        FavoritesList(success = state, onLoadMore = onLoadMore, onEntryClick = onEntryClick)
                    }
            }
        }
    }
}

/**
 * The list itself, plus paging via [EndOfListTrigger] (decision D-H — shared with
 * `LibraryScreen.LibraryList`/`DiscoverScreen.DiscoverList`, extracted in task 9b.3).
 * [FavoritesUiState.Success.pageError] renders as a small footer row rather than replacing the
 * list; tapping it retries by calling [onLoadMore] again — mirroring `LibraryList`/`DiscoverList`.
 */
@Composable
private fun FavoritesList(
    success: FavoritesUiState.Success,
    onLoadMore: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = success.entries
    val listState = rememberLazyListState()

    EndOfListTrigger(listState = listState, itemCount = entries.size, onTriggered = onLoadMore)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by entry id: without a key, LazyColumn identifies items by index and a refresh
        // that reorders the list re-uses the wrong composable state for every row.
        items(items = entries, key = LibraryEntry::id) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
                MediaCard(
                    media = entry.media,
                    status = entry.status,
                    score = entry.score,
                    onClick = { onEntryClick(entry) },
                )
                CountdownBadge(daysUntil = entry.media.daysUntilNextEpisode)
            }
        }
        if (success.loadingMore) {
            item { LoadingState() }
        } else if (success.pageError != null) {
            item {
                Text(
                    text = stringResource(R.string.favorites_page_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLoadMore)
                            .padding(all = 12.dp),
                )
            }
        }
    }
}
