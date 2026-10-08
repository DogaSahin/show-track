package com.anarky.showtrack.feature.favorites

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LargeTitleScaffold
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.SkeletonBlock
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.designsystem.theme.FavoriteHeart
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.MediaType
import kotlinx.coroutines.flow.Flow

private val ShelfPosterWidth = 100.dp
private const val SKELETON_POSTERS = 3
private const val PODIUM_CENTRE_WEIGHT = 1.25f

/**
 * The stateful entry point. Refreshes on every resume ([FavoritesViewModel]'s own KDoc says why)
 * and turns the ViewModel's removal events into snackbars, with Undo on a removal.
 */
@Composable
fun FavoritesScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    onSeeAll: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    RemovalSnackbars(events = viewModel.events, snackbarHostState = snackbarHostState, onUndo = viewModel::undo)
    FavoritesScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpen = onEntryClick,
        onRemove = viewModel::remove,
        onSeeAll = onSeeAll,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The stateless half: podium, then the Anime and TV shelves, each hidden when empty. A failed
 * refresh over a populated screen keeps it and shows the stale banner; a failed shelf page is a
 * footer at the end of that shelf.
 */
@Suppress("LongParameterList")
@Composable
internal fun FavoritesScreen(
    state: FavoritesUiState,
    onRetry: () -> Unit,
    onLoadMore: (MediaType) -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    onSeeAll: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    LargeTitleScaffold(
        title = stringResource(R.string.favorites_title),
        snackbarHostState = snackbarHostState,
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is FavoritesUiState.Loading -> FavoritesSkeleton()
                is FavoritesUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.favorites_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is FavoritesUiState.Success ->
                    if (state.isEmpty && !state.isStale) {
                        FavoritesEmpty()
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(space = 12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (state.isStale) item(key = "stale") { StaleDataBanner(onRetry = onRetry) }
                            if (state.podium.isNotEmpty()) {
                                item(
                                    key = "podium",
                                ) { FavoritesPodium(ranked = state.podium, onOpen = onOpen, onRemove = onRemove) }
                            }
                            MediaType.entries.forEach { type ->
                                val shelf = state.shelf(type)
                                if (shelf.entries.isNotEmpty()) {
                                    item(key = "shelf-${type.wire()}") {
                                        FavoriteShelfRow(
                                            type = type,
                                            shelf = shelf,
                                            onLoadMore = { onLoadMore(type) },
                                            onOpen = onOpen,
                                            onRemove = onRemove,
                                            onSeeAll = { onSeeAll(type) },
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}

/** "Anime" / "TV" with See all, then posters best first, loading more as you scroll sideways. */
@Suppress("LongParameterList")
@Composable
private fun FavoriteShelfRow(
    type: MediaType,
    shelf: FavoriteShelf,
    onLoadMore: () -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    onSeeAll: () -> Unit,
) {
    val rowState = rememberLazyListState()
    EndOfListTrigger(listState = rowState, itemCount = shelf.entries.size, onTriggered = onLoadMore)
    Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
        ) {
            Text(
                text = stringResource(type.rowTitleRes()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSeeAll) { Text(text = stringResource(R.string.favorites_see_all)) }
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(space = 10.dp),
        ) {
            items(items = shelf.entries, key = LibraryEntry::id) { entry ->
                FavoritePoster(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onRemove = { onRemove(entry) },
                    modifier = Modifier.width(ShelfPosterWidth),
                )
            }
            if (shelf.loadingMore) {
                item { LoadingState(modifier = Modifier.width(ShelfPosterWidth)) }
            } else if (shelf.pageError != null) {
                item {
                    Text(
                        text = stringResource(R.string.favorites_page_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(ShelfPosterWidth).clickable(onClick = onLoadMore).padding(all = 8.dp),
                    )
                }
            }
        }
    }
}

private fun MediaType.rowTitleRes(): Int =
    when (this) {
        MediaType.ANIME -> R.string.favorites_row_anime
        MediaType.TV -> R.string.favorites_row_tv
    }

@Composable
private fun FavoritesEmpty() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(space = 10.dp, alignment = Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_heart),
            contentDescription = null,
            tint = FavoriteHeart,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.favorites_empty_message),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.favorites_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A skeleton podium and one skeleton shelf. */
@Composable
private fun FavoritesSkeleton() {
    Column(
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp), verticalAlignment = Alignment.Bottom) {
            SkeletonBlock(modifier = Modifier.weight(1f).height(150.dp))
            SkeletonBlock(modifier = Modifier.weight(PODIUM_CENTRE_WEIGHT).height(190.dp))
            SkeletonBlock(modifier = Modifier.weight(1f).height(150.dp))
        }
        SkeletonBlock(modifier = Modifier.width(80.dp).height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(space = 10.dp)) {
            repeat(
                SKELETON_POSTERS,
            ) { SkeletonBlock(modifier = Modifier.size(width = ShelfPosterWidth, height = 150.dp)) }
        }
    }
}

/**
 * Removal outcomes as snackbars: "Removed X from favorites" with Undo, or the edit error when the
 * server refused (the poster is already back by then). Shared by the tab and the See all grid.
 */
@Composable
internal fun RemovalSnackbars(
    events: Flow<FavoritesEvent>,
    snackbarHostState: SnackbarHostState,
    onUndo: (LibraryEntry) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is FavoritesEvent.Removed -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.favorites_removed_snackbar, event.entry.media.title),
                            actionLabel = resources.getString(R.string.favorites_removed_undo),
                        )
                    if (result == SnackbarResult.ActionPerformed) onUndo(event.entry)
                }
                FavoritesEvent.EditFailed ->
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.favorites_edit_error),
                    )
            }
        }
    }
}
