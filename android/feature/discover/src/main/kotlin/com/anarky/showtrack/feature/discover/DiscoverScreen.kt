package com.anarky.showtrack.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LargeTitleScaffold
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.SkeletonBlock
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.Recommendation

private const val TOP_PICK_KEY = "top-pick"
private const val SKELETON_POSTERS = 3

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
 * listed here indefinitely.
 *
 * Each [DiscoverViewModel.added] event becomes one "X added to Planned" snackbar whose Open action
 * goes to that title's details. Collected here, not in the stateless half, so that half stays
 * drivable from a test without a ViewModel.
 */
@Composable
fun DiscoverScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.added.collect { media ->
            val result =
                snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.discover_added_snackbar, media.title),
                    actionLabel = resources.getString(R.string.discover_added_snackbar_action),
                )
            if (result == SnackbarResult.ActionPerformed) onNavigateToDetail(media.id)
        }
    }
    DiscoverScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onAdd = viewModel::add,
        onRowClick = { recommendation -> onNavigateToDetail(recommendation.media.id) },
        onSearch = onNavigateToSearch,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`/`SearchScreen`'s pattern.
 *
 * The parameter count trips detekt's `LongParameterList`; suppressed rather than bundling the
 * callbacks into an `Actions` holder class that would exist for this one call site only.
 */
@Suppress("LongParameterList")
@Composable
internal fun DiscoverScreen(
    state: DiscoverUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: (Recommendation) -> Unit,
    onRowClick: (Recommendation) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    LargeTitleScaffold(
        title = stringResource(R.string.discover_title),
        snackbarHostState = snackbarHostState,
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is DiscoverUiState.Loading -> DiscoverSkeleton()
                is DiscoverUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.discover_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is DiscoverUiState.Success ->
                    // isStale (task 9c.8, E-M): the banner sits ABOVE the content rather than
                    // replacing it — a resume's failed background refetch leaves picks that are
                    // still worth showing, just not guaranteed current.
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.isStale) {
                            StaleDataBanner(onRetry = onRetry)
                        }
                        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
                            if (state.items.isEmpty()) {
                                EmptyState(
                                    message = stringResource(R.string.discover_empty_message),
                                    actionLabel = stringResource(R.string.discover_empty_search_button),
                                    onAction = onSearch,
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
 * The top pick, then the shelves, plus paging via [EndOfListTrigger] (decision D-H).
 *
 * `remember(items)` around the derivation: `toDiscoverLayout` walks the whole paged list, and this
 * composable recomposes on every frame while the list is near its end (that is exactly what
 * [EndOfListTrigger]'s own `derivedStateOf` is reacting to).
 *
 * **`rearmKey = items.size` is load-bearing, not tidiness.** `itemCount` here is this LazyColumn's
 * own index space (the top pick plus the shelves) — but a page arrives as items, and the endpoint
 * orders by score rather than by seed, so a page routinely lands entirely inside shelves that
 * already exist and leaves that count untouched. Without a key that moves with the items, the
 * trigger's cached `derivedStateOf` stays latched at `true`, `LaunchedEffect` sees no edge, and
 * Discover stops paging while still holding a cursor. See [EndOfListTrigger]'s own KDoc.
 *
 * [DiscoverUiState.Success.pageError] renders as a footer rather than replacing the shelves;
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
    val layout = remember(items) { items.toDiscoverLayout() }
    val listState = rememberLazyListState()
    val topPick = layout.topPick

    EndOfListTrigger(
        listState = listState,
        itemCount = layout.shelves.size + if (topPick != null) 1 else 0,
        rearmKey = items.size,
        onTriggered = onLoadMore,
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(space = 24.dp),
    ) {
        if (topPick != null) {
            item(key = TOP_PICK_KEY) {
                TopPickCard(
                    recommendation = topPick,
                    showAddError = success.addError?.mediaId == topPick.media.id,
                    onAdd = { onAdd(topPick) },
                    onDetails = { onRowClick(topPick) },
                )
            }
        }
        items(items = layout.shelves, key = DiscoverShelf::seedMediaId) { shelf ->
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

/** One top-pick card and one shelf in grey, standing in for the layout that is about to load. */
@Composable
private fun DiscoverSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
        SkeletonBlock(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(220.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 6.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        ) {
            SkeletonBlock(modifier = Modifier.width(110.dp).height(10.dp))
            SkeletonBlock(modifier = Modifier.width(180.dp).height(14.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(space = 10.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            repeat(SKELETON_POSTERS) {
                SkeletonBlock(modifier = Modifier.size(width = 96.dp, height = 144.dp))
            }
        }
    }
}
