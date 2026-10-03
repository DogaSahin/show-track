package com.anarky.showtrack.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LargeTitleScaffold
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
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
 * round trip.
 */
@Composable
fun LibraryScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val airingSoon by viewModel.airingSoon.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshOverview()
        onPauseOrDispose { }
    }
    LibraryScreen(
        state = state,
        filter = filter,
        stats = stats,
        airingSoon = airingSoon,
        onStatusSelected = viewModel::selectStatus,
        onSortSelected = viewModel::selectSort,
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
 * [filter] is a separate parameter from [state] on purpose: it drives the two dropdowns directly,
 * and it stays valid (and keeps showing whatever the user picked) even while [state] is
 * [LibraryUiState.Loading] or [LibraryUiState.Error] — see [LibraryViewModel.filter]'s KDoc.
 *
 * The filter row sits outside the list, so it stays pinned while the list scrolls and the large
 * title collapses; a divider appears under it once the list has moved.
 *
 * The parameter count trips detekt's `LongParameterList`; suppressed rather than bundling the
 * callbacks into an `Actions` holder that would exist for this one call site only.
 */
@Suppress("LongParameterList")
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    filter: LibraryFilter,
    stats: LibraryStats?,
    airingSoon: List<LibraryEntry>,
    onStatusSelected: (UserMediaStatus?) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
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
            if (listState.canScrollBackward) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
                when (state) {
                    is LibraryUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                    is LibraryUiState.Error ->
                        ErrorState(
                            message = stringResource(R.string.library_error_message),
                            onRetry = onRetry,
                            modifier = Modifier.fillMaxSize(),
                        )
                    is LibraryUiState.Success ->
                        LibraryContent(
                            success = state,
                            filter = filter,
                            airingSoon = if (filter.status == null) airingSoon else emptyList(),
                            listState = listState,
                            onShowAll = { onStatusSelected(null) },
                            onRetry = onRetry,
                            onLoadMore = onLoadMore,
                            onEntryClick = onEntryClick,
                        )
                }
            }
        }
    }
}

/**
 * The stale banner above the list (it never replaces it), then the list or its empty state.
 *
 * The empty message differs by filter: "Nothing in your library yet" is only true on the default
 * view; under a filter it would read as data loss, so it says "Nothing here" and offers to clear a
 * status filter.
 */
@Suppress("LongParameterList")
@Composable
private fun LibraryContent(
    success: LibraryUiState.Success,
    filter: LibraryFilter,
    airingSoon: List<LibraryEntry>,
    listState: LazyListState,
    onShowAll: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (success.isStale) StaleDataBanner(onRetry = onRetry)
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            if (success.entries.isEmpty()) {
                EmptyState(
                    message =
                        stringResource(
                            if (filter.isDefault) R.string.library_empty_default else R.string.library_empty_filtered,
                        ),
                    actionLabel = if (filter.status != null) stringResource(R.string.library_empty_show_all) else null,
                    onAction = if (filter.status != null) onShowAll else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LibraryList(
                    success = success,
                    airingSoon = airingSoon,
                    listState = listState,
                    onLoadMore = onLoadMore,
                    onEntryClick = onEntryClick,
                )
            }
        }
    }
}

/**
 * "Airing soon" (when there is one), then the list, plus paging via [EndOfListTrigger].
 *
 * `itemCount` is this LazyColumn's own index space, the airing row included, so the trigger
 * measures "near the end" against what is actually laid out. [LibraryViewModel.loadMore]'s own
 * re-entry guard is what makes it SAFE for this call site to fire without checking whether a fetch
 * is already in flight.
 *
 * [LibraryUiState.Success.pageError] renders as a footer rather than replacing the list; tapping
 * it retries by calling [onLoadMore] again.
 */
@Composable
private fun LibraryList(
    success: LibraryUiState.Success,
    airingSoon: List<LibraryEntry>,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    val entries = success.entries
    val headerCount = if (airingSoon.isNotEmpty()) 1 else 0

    EndOfListTrigger(listState = listState, itemCount = entries.size + headerCount, onTriggered = onLoadMore)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        if (airingSoon.isNotEmpty()) {
            item(key = AIRING_SOON_KEY) { AiringSoonSection(entries = airingSoon, onEntryClick = onEntryClick) }
        }
        items(items = entries, key = LibraryEntry::id) { entry ->
            LibraryRow(entry = entry, onClick = { onEntryClick(entry) })
        }
        if (success.loadingMore) {
            item { LoadingState() }
        } else if (success.pageError != null) {
            item {
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
