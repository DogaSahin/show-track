package com.anarky.showtrack.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.model.Recommendation

private val PosterWidth = 64.dp

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen`/`SearchScreen` use.
 *
 * [onNavigateToDetail] takes the bare media id straight off [Recommendation.media]: unlike a search
 * result, a recommendation's `media` IS a `PersistedMedia` and already carries one (no add-first
 * workaround — see this module's `DiscoverNavigation.kt`).
 */
@Composable
fun DiscoverScreen(
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
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
                    if (state.items.isEmpty()) {
                        EmptyState(
                            message = stringResource(R.string.discover_empty_message),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DiscoverList(success = state, onLoadMore = onLoadMore, onAdd = onAdd, onRowClick = onRowClick)
                    }
            }
        }
    }
}

/**
 * The list itself, plus paging via [EndOfListTrigger] (decision D-H — shared with
 * `LibraryScreen.LibraryList`, extracted this task). [DiscoverUiState.Success.pageError] renders as
 * a small footer row rather than replacing the list, tapping it retries by calling [onLoadMore]
 * again — mirroring `LibraryList`/`SearchResultsList`.
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
    val listState = rememberLazyListState()

    EndOfListTrigger(listState = listState, itemCount = items.size, onTriggered = onLoadMore)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by media id: a recommendation's media IS persisted and therefore has a stable id,
        // unlike a search result (decision C-N) — LazyColumn would otherwise identify rows by
        // index, and the optimistic remove/restore (D-I) reorders exactly this list.
        items(items = items, key = { it.media.id }) { recommendation ->
            Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
                DiscoverRow(
                    recommendation = recommendation,
                    onRowClick = { onRowClick(recommendation) },
                    onAddClick = { onAdd(recommendation) },
                )
                if (success.addError?.mediaId == recommendation.media.id) {
                    Text(
                        text = stringResource(R.string.discover_add_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = { onAdd(recommendation) })
                                .padding(horizontal = 4.dp),
                    )
                }
            }
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
                            .padding(all = 12.dp),
                )
            }
        }
    }
}

/**
 * One recommendation. Tapping the ROW opens Detail — [recommendation]'s media carries a real id
 * (unlike a search result), so there is no add-first workaround needed the way `SearchResultRow`
 * needs one. The one-tap ADD is a separate, trailing affordance: [onAddClick] fires
 * [DiscoverViewModel.add], which removes this row from the list immediately (decision D-I) — there
 * is no "adding" spinner on this row the way `SearchResultRow`'s is, because by the time an add
 * could show one the row is already gone from the list.
 *
 * Deliberately NOT `MediaCard`: that component renders a library [com.anarky.showtrack.core.model.UserMediaStatus]
 * badge and a score, neither of which a recommendation has — a recommendation is by construction
 * not in the library yet (see [com.anarky.showtrack.core.model.Recommendation]'s KDoc). This
 * composes [MediaCover] plus text instead, the same choice `SearchResultRow` makes for the same
 * reason, plus the reason line [MediaCover] and `MediaCard` have no slot for at all.
 */
@Composable
private fun DiscoverRow(
    recommendation: Recommendation,
    onRowClick: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = recommendation.media
    val reason = recommendation.reason
    Card(onClick = onRowClick, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(all = 12.dp)) {
            MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(PosterWidth))
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(weight = 1f),
                verticalArrangement = Arrangement.spacedBy(space = 4.dp),
            ) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(media.year?.toString(), media.genres.takeIf { it.isNotEmpty() }?.joinToString())
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle.joinToString(separator = " · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ONE seed, never all of them — RecommendationReason's own KDoc. A separate,
                // genres-free string when matchedGenres is empty — the SAME guard the subtitle
                // above applies to media.genres — so this never renders a dangling "— " with
                // nothing after it.
                val reasonText =
                    if (reason.matchedGenres.isEmpty()) {
                        stringResource(R.string.discover_reason, reason.seedTitle)
                    } else {
                        stringResource(
                            R.string.discover_reason_with_genres,
                            reason.seedTitle,
                            reason.matchedGenres.joinToString(),
                        )
                    }
                Text(
                    text = reasonText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onAddClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.discover_add_content_description, media.title),
                )
            }
        }
    }
}
