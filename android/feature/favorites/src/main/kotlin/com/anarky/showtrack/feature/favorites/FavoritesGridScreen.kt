package com.anarky.showtrack.feature.favorites

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EndOfGridTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.MediaType

private const val GRID_COLUMNS = 3

/**
 * "Anime favorites" / "TV favorites": every favourite of one type as a grid, with a sort and the
 * same long-press removal as the tab. Refreshes on resume, like the tab, because a favourite or a
 * score can change on show details.
 */
@Composable
fun FavoritesGridScreen(
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesGridViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    RemovalSnackbars(events = viewModel.events, snackbarHostState = snackbarHostState, onUndo = viewModel::undo)
    FavoritesGridScreen(
        type = viewModel.type,
        state = state,
        sort = sort,
        onBack = { backDispatcher?.onBackPressed() },
        onSortSelected = viewModel::selectSort,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpen = onEntryClick,
        onRemove = viewModel::remove,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The count ("5 shows") appears only once every page has loaded: the server sends no total, and a
 * count of what happens to be loaded so far would be wrong while more pages remain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
internal fun FavoritesGridScreen(
    type: MediaType,
    state: FavoritesGridUiState,
    sort: FavoritesGridSort,
    onBack: () -> Unit,
    onSortSelected: (FavoritesGridSort) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(type.gridTitleRes())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.favorites_back),
                        )
                    }
                },
                actions = { SortMenu(sort = sort, onSortSelected = onSortSelected) },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is FavoritesGridUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                is FavoritesGridUiState.Error ->
                    ErrorState(
                        message = stringResource(R.string.favorites_error_message),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is FavoritesGridUiState.Success ->
                    FavoritesGrid(
                        success = state,
                        onRetry = onRetry,
                        onLoadMore = onLoadMore,
                        onOpen = onOpen,
                        onRemove = onRemove,
                    )
            }
        }
    }
}

@Composable
private fun FavoritesGrid(
    success: FavoritesGridUiState.Success,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
) {
    val shelf = success.shelf
    val gridState = rememberLazyGridState()
    EndOfGridTrigger(gridState = gridState, itemCount = shelf.entries.size, onTriggered = onLoadMore)
    Column(modifier = Modifier.fillMaxSize()) {
        if (success.isStale) StaleDataBanner(onRetry = onRetry)
        if (shelf.nextCursor == null) {
            Text(
                text = pluralStringResource(R.plurals.favorites_grid_count, shelf.entries.size, shelf.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(space = 9.dp),
            verticalArrangement = Arrangement.spacedBy(space = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = shelf.entries, key = LibraryEntry::id) { entry ->
                FavoritePoster(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onRemove = { onRemove(entry) },
                    showTypeAndYear = true,
                )
            }
            if (shelf.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) { LoadingState() }
            } else if (shelf.pageError != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.favorites_page_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadMore).padding(all = 12.dp),
                    )
                }
            }
        }
    }
}

/** "By score ▾" / "By title ▾". TalkBack hears "Sort: By score". */
@Composable
private fun SortMenu(
    sort: FavoritesGridSort,
    onSortSelected: (FavoritesGridSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(sort.labelRes())
    val description = stringResource(R.string.favorites_sort_description, current)
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = description
                    onClick {
                        expanded = true
                        true
                    }
                },
        ) {
            Text(text = "$current ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FavoritesGridSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(option.labelRes())) },
                    trailingIcon =
                        if (option ==
                            sort
                        ) {
                            ({ Text(text = "✓", color = MaterialTheme.colorScheme.primary) })
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        onSortSelected(option)
                    },
                )
            }
        }
    }
}

private fun FavoritesGridSort.labelRes(): Int =
    when (this) {
        FavoritesGridSort.SCORE -> R.string.favorites_sort_score
        FavoritesGridSort.TITLE -> R.string.favorites_sort_title
    }

private fun MediaType.gridTitleRes(): Int =
    when (this) {
        MediaType.ANIME -> R.string.favorites_see_all_title_anime
        MediaType.TV -> R.string.favorites_see_all_title_tv
    }
