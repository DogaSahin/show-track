package com.anarky.showtrack.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.UserMediaStatus

private val PosterWidth = 64.dp
private val AddingSpinnerSize = 20.dp

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen`/`DetailScreen` use.
 *
 * [onNavigateToDetail] takes a bare media id, not a [MediaSummary] or a route: the id this screen
 * navigates with is never one a search result carried in (it has none — decision C-N), only the id
 * [SearchViewModel.navigateToDetail] emits after `POST /v1/library` mints it. Collecting a `Flow`
 * with `LaunchedEffect`, not `collectAsStateWithLifecycle`: [SearchViewModel.navigateToDetail] is a
 * one-shot event stream, not state — see its KDoc for why a `StateFlow` here would re-navigate on
 * rotation.
 */
@Composable
fun SearchScreen(
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navigateToDetail.collect { mediaId -> onNavigateToDetail(mediaId) }
    }

    SearchScreen(
        state = state,
        query = query,
        onQueryChange = viewModel::onQueryChange,
        onResultClick = viewModel::add,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`'s pattern.
 *
 * Seven parameters trips detekt's `LongParameterList` (threshold 6); suppressed rather than
 * bundling the four callbacks into an `Actions` holder class, which would exist for this one call
 * site only — `LibraryScreen`'s own justification for the same suppression, one screen earlier.
 */
@Suppress("LongParameterList")
@Composable
internal fun SearchScreen(
    state: SearchUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onResultClick: (MediaSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(text = stringResource(R.string.search_field_label)) },
            placeholder = { Text(text = stringResource(R.string.search_field_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
        )
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            when (state) {
                is SearchUiState.Idle ->
                    EmptyState(
                        message = stringResource(R.string.search_idle_message),
                        modifier = Modifier.fillMaxSize(),
                    )
                is SearchUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                is SearchUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.search_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is SearchUiState.Success ->
                    SearchContent(success = state, onLoadMore = onLoadMore, onResultClick = onResultClick)
            }
        }
    }
}

/**
 * The degraded-provider banner sits above the list (or the empty state) whenever
 * [SearchResults.isDegraded][com.anarky.showtrack.core.model.SearchResults.isDegraded] — never
 * blocking, since the results that DID come back are still worth showing underneath it.
 */
@Composable
private fun SearchContent(
    success: SearchUiState.Success,
    onLoadMore: () -> Unit,
    onResultClick: (MediaSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (success.results.isDegraded) {
            DegradedBanner(providers = success.results.degraded)
        }
        if (success.results.items.isEmpty()) {
            EmptyState(message = stringResource(R.string.search_empty_message), modifier = Modifier.fillMaxSize())
        } else {
            SearchResultsList(success = success, onLoadMore = onLoadMore, onResultClick = onResultClick)
        }
    }
}

@Composable
private fun DegradedBanner(
    providers: List<MediaSource>,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            text = stringResource(R.string.search_degraded_notice, providers.joinToString { it.name }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().padding(all = 12.dp),
        )
    }
}

/**
 * [onLoadMore] fires once each time the last visible row reaches the end — the same
 * `derivedStateOf`/`LaunchedEffect(key)` shape `LibraryScreen`'s `LibraryList` uses, so scrolling
 * that merely holds the last row on screen does not keep re-calling it every frame.
 * [SearchViewModel.loadMore]'s own re-entrancy guard is what makes that call actually safe, not
 * just unlikely to matter.
 */
@Composable
private fun SearchResultsList(
    success: SearchUiState.Success,
    onLoadMore: () -> Unit,
    onResultClick: (MediaSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = success.results.items
    val listState = rememberLazyListState()

    val shouldLoadMore by
        remember(items) {
            derivedStateOf {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: -1
                items.isNotEmpty() && lastVisibleIndex >= items.lastIndex
            }
        }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && success.results.hasMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by (source, externalId) — a search result's own identity (decision C-N); it has no
        // other id to key on.
        items(items = items, key = { "${it.source}:${it.externalId}" }) { summary ->
            SearchResultRow(
                summary = summary,
                adding = success.adding == summary.externalId,
                addError = success.addError?.takeIf { it.externalId == summary.externalId },
                onClick = { onResultClick(summary) },
            )
        }
        if (success.loadingMore) {
            item { LoadingState() }
        } else if (success.pageError != null) {
            item {
                Text(
                    text = stringResource(R.string.search_page_error),
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
 * One search result. Deliberately NOT `MediaCard`: that component is built for a [Media] carrying
 * a library [UserMediaStatus] and a score, neither of which a [MediaSummary] has (nor even the
 * same type — `MediaCard` would not compile against a [MediaSummary]).
 * Passing null/fake values through just to reuse the card would misrepresent a title as "in your
 * library, unrated" when it may not be there at all, so this composes [MediaCover] plus text
 * instead, matching `MediaCard`'s own internal layout without pretending to the status it does not
 * have.
 *
 * Tapping the row while [adding] is true is a no-op ([Card]'s `enabled = false`), and the trailing
 * spinner is that row's OWN affordance — [SearchUiState.Success.adding] holds one externalId, so
 * only the tapped row shows it (decision from the task brief), not the whole list.
 */
@Composable
private fun SearchResultRow(
    summary: MediaSummary,
    adding: Boolean,
    addError: AddFailure?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, enabled = !adding, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(all = 12.dp)) {
            MediaCover(coverImageUrl = summary.coverImageUrl, modifier = Modifier.width(PosterWidth))
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(weight = 1f),
                verticalArrangement = Arrangement.spacedBy(space = 4.dp),
            ) {
                Text(
                    text = summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(summary.year?.toString(), summary.genres.takeIf { it.isNotEmpty() }?.joinToString())
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle.joinToString(separator = " · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // addError's copy is worded for what is actually known — the add may have already
                // succeeded server-side (LibraryRepositoryImpl.add's post-add refresh() wrinkle,
                // carried forward from task 9a.5's review) — and tapping this row again is safe.
                if (addError != null) {
                    Text(
                        text = stringResource(R.string.search_add_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (adding) {
                CircularProgressIndicator(modifier = Modifier.size(AddingSpinnerSize))
            }
        }
    }
}
