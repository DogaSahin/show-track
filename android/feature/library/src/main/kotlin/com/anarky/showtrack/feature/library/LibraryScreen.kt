package com.anarky.showtrack.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.CountdownBadge
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCard
import com.anarky.showtrack.core.designsystem.component.StatusTabRow
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.UserMediaStatus

/**
 * The stateful entry point. `hiltViewModel()` is the only line in this module that touches DI:
 * it resolves [LibraryViewModel] out of the nearest `ViewModelStoreOwner`'s Hilt-backed factory,
 * so the ViewModel's `LibraryRepository` arrives from the singleton component with no wiring
 * here at all.
 *
 * [onEntryClick] hands the whole [LibraryEntry] to the caller rather than a raw id, so the
 * navigation entry point below can pull `entry.media.id` — the entry's OWN id is a different
 * identifier that `DetailRoute` does not take (see `LibraryNavigation.kt`).
 *
 * [onSearchClick] is the same shape: this screen stays ignorant of navigation, so it hands the
 * tap up rather than knowing `SearchRoute` exists. `LibraryNavigation.kt` is what turns it into
 * an `onNavigate` call (Gap 1, Phase 9a device walkthroughs — the search screen existed and had
 * no door in).
 */
@Composable
fun LibraryScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the latter keeps collecting while the
    // screen is in the background, which is what makes the ViewModel's WhileSubscribed(5s)
    // upstream never stop.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    LibraryScreen(
        state = state,
        filter = filter,
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
 * ViewModel — `AuthScreen`'s pattern, applied here.
 *
 * [filter] is a separate parameter from [state] on purpose: it drives the tab row and the sort
 * menu directly, and it stays valid (and keeps showing whatever the user tapped) even while
 * [state] is [LibraryUiState.Loading] or [LibraryUiState.Error] — see
 * [LibraryViewModel.filter]'s KDoc.
 *
 * Eight parameters (now nine, with [onSearchClick]) trips detekt's `LongParameterList`
 * (threshold 6); suppressed rather than bundling the callbacks into an `Actions` holder class,
 * which would exist for this one call site only and would still need the same number of fields —
 * indirection without fewer moving parts.
 */
@Suppress("LongParameterList")
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    filter: LibraryFilter,
    onStatusSelected: (UserMediaStatus?) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LibraryHeader(sort = filter.sort, onSortSelected = onSortSelected, onSearchClick = onSearchClick)
        StatusTabRow(selected = filter.status, onStatusSelected = onStatusSelected)
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
                    if (state.entries.isEmpty()) {
                        EmptyState(
                            message =
                                stringResource(
                                    if (filter.isDefault) {
                                        R.string.library_empty_default
                                    } else {
                                        R.string.library_empty_filtered
                                    },
                                ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LibraryList(
                            success = state,
                            onLoadMore = onLoadMore,
                            onEntryClick = onEntryClick,
                        )
                    }
            }
        }
    }
}

/**
 * The title, the search action (Gap 1 — the only route into `:feature:search`), and the sort
 * control (C-J's companion decision for `LibrarySort`'s three values).
 */
@Composable
private fun LibraryHeader(
    sort: LibrarySort,
    onSortSelected: (LibrarySort) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.library_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(weight = 1f),
        )
        IconButton(onClick = onSearchClick) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.library_search_content_description),
            )
        }
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(text = stringResource(R.string.library_sort_button, stringResource(sort.labelRes())))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(option.labelRes()),
                                fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSortSelected(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * [LibrarySort]'s three values, presented as their own strings rather than `LibrarySort.name` —
 * the enum's constant names ("NEXT_EPISODE_DATE") are Kotlin identifiers, not UI copy (C-E).
 */
private fun LibrarySort.labelRes(): Int =
    when (this) {
        LibrarySort.TITLE -> R.string.library_sort_title
        LibrarySort.SCORE -> R.string.library_sort_score
        LibrarySort.NEXT_EPISODE_DATE -> R.string.library_sort_next_episode
    }

/**
 * The list itself, plus paging. [success] is the whole [LibraryUiState.Success] rather than its
 * fields exploded into parallel parameters — that keeps `entries`/`loadingMore`/`pageError`
 * together as the one cohesive value they already are upstream, and keeps this composable's
 * parameter count under detekt's `LongParameterList` threshold without a suppression.
 *
 * [onLoadMore] is called once each time the last visible row reaches the end of the list:
 * `shouldLoadMore` below only flips `false` → `true` at that moment, and
 * `LaunchedEffect(shouldLoadMore)` only re-runs its block when the KEY it is given actually
 * changes value — so scrolling that merely holds the last row on screen does not keep re-calling
 * [onLoadMore] every frame. [LibraryViewModel.loadMore]'s own re-entry guard is still the thing
 * this composable relies on for correctness, though: it is what makes it SAFE, rather than merely
 * unlikely, for this call site to invoke [onLoadMore] without first checking whether a fetch is
 * already in flight.
 *
 * [LibraryUiState.Success.pageError] renders as a small footer row rather than replacing the list
 * (see its KDoc for why it must never do that) — tapping it retries by calling [onLoadMore] again.
 */
@Composable
private fun LibraryList(
    success: LibraryUiState.Success,
    onLoadMore: () -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = success.entries
    val listState = rememberLazyListState()

    // `remember(entries)`, not a bare `remember { }`: entries is a new List reference every time
    // it changes (a fresh page appended, or a filter swapping it out entirely), and a
    // derivedStateOf whose calculation lambda closed over a STALE `entries` from the first
    // composition would keep comparing the scroll position against a list that no longer matches
    // what's on screen — a classic stale-closure trap with LaunchedEffect/derivedStateOf.
    val shouldLoadMore by
        remember(entries) {
            derivedStateOf {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: -1
                entries.isNotEmpty() && lastVisibleIndex >= entries.lastIndex
            }
        }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

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
            // Not loading AND pageError != null: the previous page fetch already finished and
            // failed. Shown as a tap-to-retry row rather than a spinner — nothing is in flight
            // for the user to wait on.
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
