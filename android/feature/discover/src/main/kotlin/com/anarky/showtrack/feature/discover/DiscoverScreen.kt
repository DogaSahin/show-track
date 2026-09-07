package com.anarky.showtrack.feature.discover

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.Recommendation

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen`/`SearchScreen` use.
 *
 * [onNavigateToDetail] takes the bare media id straight off [Recommendation.media]: unlike a search
 * result, a recommendation's `media` IS a `PersistedMedia` and already carries one (no add-first
 * workaround — see this module's `DiscoverNavigation.kt`).
 *
 * [LifecycleResumeEffect] is what makes [DiscoverViewModel]'s decision to refetch on resume (task
 * 9c.8, E-M — see that class's own KDoc for the full reasoning) actually real: it is the ONLY
 * production caller of [DiscoverViewModel.refresh] for the initial load AND for a resume, the
 * identical mechanism `FavoritesScreen`/`ProfileScreen` use for the identical reason — a
 * `NavBackStackEntry`-scoped ViewModel survives a trip to Detail or Search and back with no code
 * path of its own that re-fetches, so without this effect a title added elsewhere would stay
 * listed here indefinitely, which is exactly the acceptance criterion this task exists for.
 */
@Composable
fun DiscoverScreen(
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscoverScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onAdd = viewModel::add,
        onRowClick = { recommendation -> onNavigateToDetail(recommendation.media.id) },
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`/`SearchScreen`'s pattern.
 *
 * Six parameters trips detekt's `LongParameterList` (threshold 6); suppressed rather than
 * bundling the four callbacks into an `Actions` holder class, which would exist for this one call
 * site only — `LibraryScreen`/`SearchScreen`'s own justification for the same suppression.
 */
@Suppress("LongParameterList")
@Composable
internal fun DiscoverScreen(
    state: DiscoverUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: (Recommendation) -> Unit,
    onRowClick: (Recommendation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.discover_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            when (state) {
                is DiscoverUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                is DiscoverUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.discover_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is DiscoverUiState.Success ->
                    // isStale (task 9c.8, E-M): the banner sits ABOVE the content rather than
                    // replacing it — a resume's failed background refetch leaves rows that are
                    // still worth showing, just not guaranteed current — mirroring
                    // `FavoritesScreen`/`ProfileScreen`'s identical StaleDataBanner placement.
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.isStale) {
                            StaleDataBanner(onRetry = onRetry)
                        }
                        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
                            if (state.items.isEmpty()) {
                                EmptyState(
                                    message = stringResource(R.string.discover_empty_message),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                DiscoverList(
                                    success = state,
                                    onLoadMore = onLoadMore,
                                    onAdd = onAdd,
                                    onRowClick = onRowClick,
                                )
                            }
                        }
                    }
            }
        }
    }
}

/**
 * The shelves, plus paging via [EndOfListTrigger] (decision D-H).
 *
 * `remember(items)` around the grouping: `toShelves` walks the whole paged list, and this
 * composable recomposes on every frame while the list is near its end (that is exactly what
 * [EndOfListTrigger]'s own `derivedStateOf` is reacting to). Regrouping per frame would be work
 * proportional to everything paged in so far, done for nothing.
 *
 * **`rearmKey = items.size` is load-bearing, not tidiness.** `itemCount` here is the SHELF count,
 * because that is this LazyColumn's index space — but a page arrives as items, and the endpoint
 * orders by score rather than by seed, so a page routinely lands entirely inside shelves that
 * already exist and leaves the shelf count untouched. Without a key that moves with the items, the
 * trigger's cached `derivedStateOf` stays latched at `true`, `LaunchedEffect` sees no edge, and
 * Discover stops paging while still holding a cursor. See [EndOfListTrigger]'s own KDoc.
 *
 * [DiscoverUiState.Success.pageError] renders as a footer rather than replacing the shelves,
 * tapping it retries by calling [onLoadMore] again — mirroring `LibraryList`/`SearchResultsList`.
 */
@Composable
private fun DiscoverList(
    success: DiscoverUiState.Success,
    onLoadMore: () -> Unit,
    onAdd: (Recommendation) -> Unit,
    onRowClick: (Recommendation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = success.items
    val shelves = remember(items) { items.toShelves() }
    val listState = rememberLazyListState()

    EndOfListTrigger(
        listState = listState,
        itemCount = shelves.size,
        rearmKey = items.size,
        onTriggered = onLoadMore,
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 24.dp),
    ) {
        items(items = shelves, key = DiscoverShelf::seedMediaId) { shelf ->
            DiscoverShelfRow(
                shelf = shelf,
                onItemClick = onRowClick,
                onAdd = onAdd,
                addErrorMediaId = success.addError?.mediaId,
            )
        }
        if (success.loadingMore) {
            item { LoadingState() }
        } else if (success.pageError != null) {
            item {
                Text(
                    text = stringResource(R.string.discover_page_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLoadMore)
                            .padding(all = 16.dp),
                )
            }
        }
    }
}
