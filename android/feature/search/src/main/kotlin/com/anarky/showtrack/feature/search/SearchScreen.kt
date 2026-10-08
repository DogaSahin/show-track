package com.anarky.showtrack.feature.search

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.displayName
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.SearchResult
import kotlinx.coroutines.launch

/**
 * [onNavigateToDetail] takes a bare media id: the screen opens a result by the id it already has,
 * or by the one resolving it returns. Nothing here adds to the library except + Add.
 */
@Composable
fun SearchScreen(
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val snackbarScope = rememberCoroutineScope()

    // Snackbars run in their own coroutine: showSnackbar suspends until it is dismissed, and an
    // open must never wait behind one. A newer message replaces the one on screen.
    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        onAction: () -> Unit = {},
    ) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarScope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.OpenDetail -> onNavigateToDetail(event.mediaId)
                is SearchEvent.Added ->
                    showSnackbar(
                        message = resources.getString(R.string.search_added_snackbar, event.title),
                        actionLabel = resources.getString(R.string.search_added_snackbar_action),
                        onAction = { onNavigateToDetail(event.mediaId) },
                    )
                SearchEvent.AddFailed -> showSnackbar(resources.getString(R.string.search_add_error))
                SearchEvent.OpenFailed -> showSnackbar(resources.getString(R.string.search_open_error))
            }
        }
    }

    SearchScreen(
        state = state,
        query = query,
        recent = recent,
        actions =
            SearchActions(
                onQueryChange = viewModel::onQueryChange,
                onSubmit = viewModel::onSubmit,
                onBack = { backDispatcher?.onBackPressed() },
                onOpen = viewModel::open,
                onAdd = viewModel::add,
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::retry,
                onRunRecent = viewModel::runRecent,
                onFillRecent = viewModel::fillQuery,
                onClearRecent = viewModel::clearRecentSearches,
            ),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** Everything the screen can ask for, so the stateless overload stays readable. */
internal data class SearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpen: (SearchResult) -> Unit = {},
    val onAdd: (SearchResult) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRunRecent: (String) -> Unit = {},
    val onFillRecent: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
)

@Suppress("LongParameterList")
@Composable
internal fun SearchScreen(
    state: SearchUiState,
    query: String,
    recent: List<String>,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SearchBar(
                query = query,
                onQueryChange = actions.onQueryChange,
                onSubmit = actions.onSubmit,
                onBack = actions.onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is SearchUiState.Idle ->
                    RecentSearchList(
                        recent = recent,
                        onRun = actions.onRunRecent,
                        onFill = actions.onFillRecent,
                        onClear = actions.onClearRecent,
                    )
                is SearchUiState.Loading -> SearchSkeleton()
                is SearchUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.search_error_message),
                        onRetry = actions.onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is SearchUiState.Success -> SearchContent(success = state, actions = actions)
            }
        }
    }
}

@Composable
private fun SearchContent(
    success: SearchUiState.Success,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (success.results.isDegraded) {
            DegradedNotice(providers = success.results.degraded)
        }
        if (success.results.items.isEmpty()) {
            EmptyState(message = stringResource(R.string.search_empty_message), modifier = Modifier.fillMaxSize())
        } else {
            SearchResultsList(success = success, actions = actions)
        }
    }
}

@Composable
private fun DegradedNotice(
    providers: List<MediaSource>,
    modifier: Modifier = Modifier,
) {
    // Names resolved with `map` (inline, so still a composable context) before joining:
    // `joinToString`'s transform is not inline and could not call displayName().
    val providerNames = providers.map { it.displayName() }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = stringResource(R.string.search_degraded_notice, providerNames.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SearchResultsList(
    success: SearchUiState.Success,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    val items = success.results.items
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = items, key = { it.media.key }) { result ->
            val key = result.media.key
            SearchResultRow(
                result = result,
                adding = success.adding == key,
                opening = success.opening == key,
                onOpen = { actions.onOpen(result) },
                onAdd = { actions.onAdd(result) },
            )
        }
        if (success.loadingMore) {
            item { LoadingState(modifier = Modifier.fillMaxWidth().padding(16.dp)) }
        } else if (success.pageError != null) {
            item {
                Text(
                    text = stringResource(R.string.search_page_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = actions.onLoadMore)
                            .padding(16.dp),
                )
            }
        }
    }
    if (success.results.hasMore && success.pageError == null) {
        EndOfListTrigger(listState = listState, itemCount = items.size, onTriggered = actions.onLoadMore)
    }
}
