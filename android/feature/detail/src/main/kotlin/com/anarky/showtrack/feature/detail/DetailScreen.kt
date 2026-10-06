package com.anarky.showtrack.feature.detail

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun DetailScreen(
    activeGroup: StateFlow<ActiveGroupState>,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val currentActiveGroup by activeGroup.collectAsStateWithLifecycle()
    val currentGroupId = (currentActiveGroup as? ActiveGroupState.Success)?.activeGroupId
    val groups = (currentActiveGroup as? ActiveGroupState.Success)?.groups.orEmpty()
    LifecycleResumeEffect(viewModel, currentGroupId) {
        viewModel.setActiveGroup(currentGroupId)
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DetailScreen(
        state = state,
        groups = groups,
        actions =
            DetailActions(
                onBack = { backDispatcher?.onBackPressed() },
                onRetry = viewModel::retry,
                onAddToLibrary = viewModel::addToLibrary,
                onRemoveFromLibrary = viewModel::removeFromLibrary,
                onScoreSelected = viewModel::setScore,
                onScoreCleared = viewModel::clearScore,
                onStatusSelected = viewModel::setStatus,
                onFavoriteToggle = viewModel::toggleFavorite,
                onAcceptCatchUp = viewModel::acceptCatchUp,
                onDismissCatchUp = viewModel::dismissCatchUp,
                episodes =
                    EpisodeActions(
                        onToggleEpisode = viewModel::toggleEpisode,
                        onToggleSeason = viewModel::toggleSeason,
                        onMarkSeason = viewModel::markSeason,
                        onRetry = viewModel::retryEpisodes,
                    ),
                onProposeToGroup = viewModel::proposeToGroup,
                onRetryGroupSection = viewModel::retryGroupSection,
                onOpenReviewEditor = viewModel::openReviewEditor,
                onSaveReview = viewModel::saveReview,
                onCancelReviewEditor = viewModel::closeReviewEditor,
                onClearReviewError = viewModel::clearReviewError,
                onDeleteReview = viewModel::deleteReview,
            ),
        activeGroupName = groups.firstOrNull { it.id == currentGroupId }?.name,
        modifier = modifier,
    )
}

/** Everything the screen can ask for, so the stateless overload stays readable. */
internal data class DetailActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAddToLibrary: () -> Unit = {},
    val onRemoveFromLibrary: () -> Unit = {},
    val onScoreSelected: (BigDecimal) -> Unit = {},
    val onScoreCleared: () -> Unit = {},
    val onStatusSelected: (UserMediaStatus) -> Unit = {},
    val onFavoriteToggle: () -> Unit = {},
    val onAcceptCatchUp: (CatchUp) -> Unit = {},
    val onDismissCatchUp: (CatchUp) -> Unit = {},
    val episodes: EpisodeActions = EpisodeActions(),
    val onProposeToGroup: (String) -> Unit = {},
    val onRetryGroupSection: () -> Unit = {},
    val onOpenReviewEditor: () -> Unit = {},
    val onSaveReview: (String, Boolean) -> Unit = { _, _ -> },
    val onCancelReviewEditor: () -> Unit = {},
    val onClearReviewError: () -> Unit = {},
    val onDeleteReview: () -> Unit = {},
)

@Suppress("LongParameterList")
@Composable
internal fun DetailScreen(
    state: DetailUiState,
    groups: List<Group>,
    actions: DetailActions,
    modifier: Modifier = Modifier,
    today: LocalDate = remember { LocalDate.now() },
    activeGroupName: String? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is DetailUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            is DetailUiState.Error ->
                ErrorState(
                    message = stringResource(R.string.detail_error_message),
                    onRetry = actions.onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            is DetailUiState.Success ->
                DetailContent(
                    success = state,
                    groups = groups,
                    activeGroupName = activeGroupName,
                    actions = actions,
                    today = today,
                    snackbarHostState = snackbarHostState,
                )
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Suppress("LongParameterList")
@Composable
private fun DetailContent(
    success: DetailUiState.Success,
    groups: List<Group>,
    activeGroupName: String?,
    actions: DetailActions,
    today: LocalDate,
    snackbarHostState: SnackbarHostState,
) {
    val (media, entry) = success.data
    val listState = rememberLazyListState()
    // Null until the user picks a range: the grid then opens on the next episode's.
    var animeRange by rememberSaveable { mutableStateOf<Int?>(null) }
    val animeColumns = animeColumns()
    // The episode list, when loaded, gives the race track its length and season ticks.
    val list = (success.episodes as? EpisodesState.Ready)?.list
    val total = list?.totalEpisodes ?: media.totalEpisodes
    // Settle the starting range once, so ticking episode 100 does not jump the grid to 101–200.
    val ready = success.episodes as? EpisodesState.Ready
    LaunchedEffect(ready != null) {
        if (ready != null && animeRange == null) animeRange = defaultAnimeRange(ready)
    }
    CatchUpSnackbar(success = success, actions = actions, snackbarHostState = snackbarHostState)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "hero") { DetailHero(media = media) }
            item(key = "genres") { GenreChips(genres = media.genres, modifier = Modifier.padding(top = 12.dp)) }
            item(key = "next") { NextEpisodeBar(media = media, modifier = Modifier.padding(top = 12.dp)) }
            item(key = "tracking") { TrackingSection(success = success, actions = actions) }
            episodeItems(
                state = success.episodes,
                anime = media.type == MediaType.ANIME,
                today = today,
                animeRange = animeRange,
                animeColumns = animeColumns,
                actions = actions.episodes.copy(onAnimeRange = { animeRange = it }),
            )
            groupItems(
                success = success,
                groups = groups,
                activeGroupName = activeGroupName,
                total = total,
                list = list,
                actions = actions,
            )
        }
        DetailTopBar(
            title = media.title,
            listState = listState,
            inLibrary = entry != null,
            onBack = actions.onBack,
            onRemove = actions.onRemoveFromLibrary,
        )
    }
}

/** 7 tiles a row, or 6 on a phone too narrow for seven 48 dp tiles. */
@Composable
private fun animeColumns(): Int =
    if (LocalConfiguration.current.screenWidthDp >= ANIME_COLUMNS_MIN_WIDTH_DP) ANIME_COLUMNS else ANIME_COLUMNS_NARROW

/** The tracking card, or Add to library when the title is not in it yet. */
@Composable
private fun TrackingSection(
    success: DetailUiState.Success,
    actions: DetailActions,
) {
    val (media, entry) = success.data
    Box(modifier = Modifier.padding(vertical = 12.dp)) {
        if (entry == null) {
            AddToLibraryButton(
                saving = success.saving,
                error = success.actionError as? DetailActionError.Add,
                onAdd = actions.onAddToLibrary,
            )
        } else {
            TrackingCard(
                entry = entry,
                totalEpisodes = (success.episodes as? EpisodesState.Ready)?.list?.totalEpisodes ?: media.totalEpisodes,
                saving = success.saving,
                error = success.actionError,
                onStatusSelected = actions.onStatusSelected,
                onScoreSelected = actions.onScoreSelected,
                onScoreCleared = actions.onScoreCleared,
                onFavoriteToggle = actions.onFavoriteToggle,
            )
        }
    }
}

/** "Also mark E4–E5 as watched?" One at a time: a newer prompt replaces the one on screen. */
@Composable
private fun CatchUpSnackbar(
    success: DetailUiState.Success,
    actions: DetailActions,
    snackbarHostState: SnackbarHostState,
) {
    val catchUp = (success.episodes as? EpisodesState.Ready)?.catchUp
    val resources = LocalResources.current
    LaunchedEffect(catchUp) {
        if (catchUp == null) return@LaunchedEffect
        val message =
            if (catchUp.fromNumber == catchUp.toNumber) {
                resources.getString(R.string.detail_catch_up_prompt_single, catchUp.fromNumber)
            } else {
                resources.getString(R.string.detail_catch_up_prompt, catchUp.fromNumber, catchUp.toNumber)
            }
        snackbarHostState.currentSnackbarData?.dismiss()
        val result =
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = resources.getString(R.string.detail_catch_up_action),
                duration = SnackbarDuration.Long,
            )
        if (result ==
            SnackbarResult.ActionPerformed
        ) {
            actions.onAcceptCatchUp(catchUp)
        } else {
            actions.onDismissCatchUp(catchUp)
        }
    }
}

/**
 * Over the backdrop: back and ⋯ on dark circles. Once the header scrolls away, a plain bar with the
 * title takes their place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    title: String,
    listState: LazyListState,
    inLibrary: Boolean,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var confirmRemove by remember { mutableStateOf(false) }
    val menu: @Composable () -> Unit = {
        if (inLibrary) MoreMenu(overlay = !scrolled, onRemove = { confirmRemove = true })
    }
    if (scrolled) {
        TopAppBar(
            title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.detail_back),
                    )
                }
            },
            actions = { menu() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    } else {
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            OverlayIconButton(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.detail_back),
                onClick = onBack,
            )
            Spacer(modifier = Modifier.weight(1f))
            menu()
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(text = stringResource(R.string.detail_remove_confirm_title)) },
            text = { Text(text = stringResource(R.string.detail_remove_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) { Text(text = stringResource(R.string.detail_remove_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRemove = false },
                ) { Text(text = stringResource(R.string.detail_remove_cancel)) }
            },
        )
    }
}

@Composable
private fun MoreMenu(
    overlay: Boolean,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val description = stringResource(R.string.detail_more)
        if (overlay) {
            OverlayIconButton(iconRes = R.drawable.ic_more, contentDescription = description, onClick = { open = true })
        } else {
            IconButton(onClick = { open = true }) {
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = description)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.detail_remove_from_library)) },
                onClick = {
                    open = false
                    onRemove()
                },
            )
        }
    }
}

/** The group (race track and members), reviews, and "Propose to a group", in that order. */
@Suppress("LongParameterList")
private fun LazyListScope.groupItems(
    success: DetailUiState.Success,
    groups: List<Group>,
    activeGroupName: String?,
    total: Int?,
    list: EpisodeList?,
    actions: DetailActions,
) {
    item(key = "group") {
        GroupSection(
            groupSection = success.groupSection,
            groupName = activeGroupName,
            meId = success.currentUserId,
            myEntry = success.data.entry,
            totalEpisodes = total,
            episodeList = list,
            onRetry = actions.onRetryGroupSection,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    item(key = "reviews") {
        ReviewsSection(
            reviews = (success.groupSection as? GroupSectionState.Loaded)?.reviews.orEmpty(),
            reviewsKnown =
                success.groupSection.let {
                    it is GroupSectionState.Loaded || it is GroupSectionState.Absent
                },
            meId = success.currentUserId,
            reviewEditor = success.reviewEditor,
            deleting = success.deletingReview,
            deleteError = success.reviewDeleteError,
            actions = actions,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
    item(key = "propose") {
        ProposeSection(
            groups = groups,
            proposing = success.proposing,
            error = success.proposeError,
            justProposedToGroupId = success.justProposedToGroupId,
            onPropose = actions.onProposeToGroup,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
