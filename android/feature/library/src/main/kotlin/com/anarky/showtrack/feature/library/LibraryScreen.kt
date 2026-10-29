package com.anarky.showtrack.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfGridTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LargeTitleScaffold
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus

private const val AIRING_SOON_KEY = "airing-soon"

/**
 * The stateful entry point. `hiltViewModel()` is the only line in this module that touches DI.
 *
 * [onEntryClick] hands the whole [LibraryEntry] to the caller rather than a raw id, so the
 * navigation entry point can pull `entry.media.id` — the entry's OWN id is a different identifier
 * that `DetailRoute` does not take (see `LibraryNavigation.kt`). [onSearchClick] is the same shape:
 * this screen stays ignorant of navigation and hands the tap up.
 *
 * The counts and the "Airing soon" row reload on every resume ([LibraryViewModel.refreshOverview]):
 * an add or status change made in Detail or Search changes both, and this ViewModel outlives that
 * round trip. On "All shows" the same call reloads Home's sections; a single-status list reloads
 * quietly through [LibraryViewModel.refreshOnReturn], so a title added elsewhere settles in.
 */
@Composable
fun LibraryScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val airingSoon by viewModel.airingSoon.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshOverview()
        viewModel.refreshOnReturn()
        onPauseOrDispose { }
    }
    LibraryScreen(
        home = home,
        state = state,
        filter = filter,
        stats = stats,
        airingSoon = airingSoon,
        onStatusSelected = viewModel::selectStatus,
        onSortSelected = viewModel::selectSort,
        onToggleSection = viewModel::toggleSection,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::refresh,
        onEntryClick = onEntryClick,
        onSearchClick = onSearchClick,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel.
 *
 * "All shows" (`filter.status == null`) renders [home], the status sections; one status renders
 * [state], that status's paged list. Both draw the same four-across poster grid.
 *
 * [filter] is a separate parameter from [state] on purpose: it drives the two dropdowns directly,
 * and it stays valid (and keeps showing whatever the user picked) even while [state] is
 * [LibraryUiState.Loading] or [LibraryUiState.Error] — see [LibraryViewModel.filter]'s KDoc.
 *
 * The filter row (and the stale banner under it) sit outside the grid, so they stay pinned while
 * the grid scrolls and the large title collapses; a divider appears under them once the grid has
 * moved. Each view keeps its own scroll position: switching status starts the new grid at the top.
 *
 * The parameter count trips detekt's `LongParameterList`; suppressed rather than bundling the
 * callbacks into an `Actions` holder that would exist for this one call site only.
 */
@Suppress("LongParameterList")
@Composable
internal fun LibraryScreen(
    home: HomeUiState,
    state: LibraryUiState,
    filter: LibraryFilter,
    stats: LibraryStats?,
    airingSoon: List<LibraryEntry>,
    onStatusSelected: (UserMediaStatus?) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onToggleSection: (LibrarySection) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberSaveable(filter.status, saver = LazyGridState.Saver) { LazyGridState() }
    LargeTitleScaffold(
        title = stringResource(R.string.library_title),
        modifier = modifier.fillMaxSize(),
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.library_search_content_description),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LibraryFilterRow(
                filter = filter,
                stats = stats,
                onStatusSelected = onStatusSelected,
                onSortSelected = onSortSelected,
            )
            if (filter.status == null && home is HomeUiState.Ready && home.isStale) {
                StaleDataBanner(onRetry = onRetry)
            }
            if (gridState.canScrollBackward) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
                if (filter.status == null) {
                    HomeContent(
                        home = home,
                        airingSoon = airingSoon,
                        gridState = gridState,
                        onToggleSection = onToggleSection,
                        onRetry = onRetry,
                        onEntryClick = onEntryClick,
                    )
                } else {
                    StatusContent(
                        state = state,
                        gridState = gridState,
                        onShowAll = { onStatusSelected(null) },
                        onLoadMore = onLoadMore,
                        onRetry = onRetry,
                        onEntryClick = onEntryClick,
                    )
                }
            }
        }
    }
}

/**
 * "All shows": Airing soon, then the status sections, in one grid. An empty Home means an empty
 * library — empty sections are dropped, and a failed load with nothing cached is [HomeUiState.Error].
 */
@Suppress("LongParameterList")
@Composable
private fun HomeContent(
    home: HomeUiState,
    airingSoon: List<LibraryEntry>,
    gridState: LazyGridState,
    onToggleSection: (LibrarySection) -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    when (home) {
        is HomeUiState.Loading -> PosterGridSkeleton(withHeader = true)
        is HomeUiState.Error ->
            ErrorState(
                message = stringResource(R.string.library_error_message),
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        is HomeUiState.Ready ->
            if (home.sections.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.library_empty_default),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PosterGrid(gridState = gridState) {
                    if (airingSoon.isNotEmpty()) {
                        item(key = AIRING_SOON_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            AiringSoonSection(
                                entries = airingSoon,
                                onEntryClick = onEntryClick,
                                modifier = Modifier.bleedPast(GridGutter),
                            )
                        }
                    }
                    homeSections(
                        sections = home.sections,
                        onToggleSection = onToggleSection,
                        onEntryClick = onEntryClick,
                    )
                }
            }
    }
}

/**
 * One status: its whole list in the grid, no row cap, paged through [EndOfGridTrigger].
 *
 * The empty message is "Nothing here", never "Nothing in your library yet": under a filter the
 * latter would read as data loss, so it offers to clear the filter instead.
 *
 * [LibraryUiState.Success.pageError] renders as a footer rather than replacing the grid; tapping
 * it retries by calling [onLoadMore] again. [LibraryViewModel.loadMore]'s own re-entry guard is
 * what makes it safe for the trigger to fire without checking whether a fetch is in flight.
 */
@Suppress("LongParameterList")
@Composable
private fun StatusContent(
    state: LibraryUiState,
    gridState: LazyGridState,
    onShowAll: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    when (state) {
        is LibraryUiState.Loading -> PosterGridSkeleton(withHeader = false)
        is LibraryUiState.Error ->
            ErrorState(
                message = stringResource(R.string.library_error_message),
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        is LibraryUiState.Success ->
            if (state.entries.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.library_empty_filtered),
                    actionLabel = stringResource(R.string.library_empty_show_all),
                    onAction = onShowAll,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EndOfGridTrigger(gridState = gridState, itemCount = state.entries.size, onTriggered = onLoadMore)
                PosterGrid(gridState = gridState) {
                    posters(entries = state.entries, keyPrefix = "status", onEntryClick = onEntryClick)
                    if (state.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) { LoadingState() }
                    } else if (state.pageError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.library_page_error),
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
    }
}

@Composable
private fun PosterGrid(
    gridState: LazyGridState,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_COLUMNS),
        state = gridState,
        contentPadding = PosterGridPadding,
        horizontalArrangement = PosterGridSpacing,
        verticalArrangement = PosterGridRowSpacing,
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}
