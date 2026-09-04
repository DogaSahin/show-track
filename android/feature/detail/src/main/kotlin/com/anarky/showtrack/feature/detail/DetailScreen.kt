package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.CountdownBadge
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.ScoreChip
import com.anarky.showtrack.core.designsystem.component.StatusTab
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

private val PosterWidth = 120.dp

// 1.0 through 10.0 in half-point steps. The floor is 1.0, not 0.0: the server's `score_range`
// CHECK constraint (backend/app/library/models.py) rejects anything below it with a 422 —
// NUMERIC(3,1) (backend decision 4-N) only fixes the PRECISION, not the range. "Unrated" already
// has its own affordance via clearScore(), so 0 was never doing double duty here.
private val ScoreOptions: List<BigDecimal> = (2..20).map { half -> BigDecimal(half).divide(BigDecimal(2)).setScale(1) }

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen` and `AuthScreen` use.
 *
 * No `mediaId` parameter: unlike the earlier skeleton, [DetailViewModel] reads it itself from the
 * `SavedStateHandle` Hilt hands it, which is already populated from `DetailRoute`'s argument by
 * the surrounding `NavBackStackEntry` (see `DetailNavigation.kt`).
 *
 * [activeGroup] is a PARAMETER, not read from a singleton — E-C's own requirement ("features
 * RECEIVE the active group, they never read a singleton for it"), `FeedScreen`'s identical shape.
 * Collected here, inside this composable's own body, so this screen reacts to a switch even though
 * `detailEntry`'s registration itself runs far less often than that (`FeedNavigation.kt`'s own KDoc
 * explains why a `StateFlow` rather than a plain value is what makes that possible).
 * [LifecycleResumeEffect] is keyed on the RESOLVED group id — only meaningful for
 * [ActiveGroupState.Success] — so [DetailViewModel.setActiveGroup] is called with the group id
 * itself (never [ActiveGroupState] as a whole): [DetailViewModel] has no reason to know the
 * difference between "still loading" and "loaded, zero groups" the way `FeedScreen` does, since
 * both collapse into the identical [GroupSectionState.Absent] outcome here.
 */
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
    DetailScreen(
        state = state,
        groups = groups,
        onRetry = viewModel::retry,
        onAddToLibrary = viewModel::addToLibrary,
        onScoreSelected = viewModel::setScore,
        onScoreCleared = viewModel::clearScore,
        onProgressChange = viewModel::setProgress,
        onStatusSelected = viewModel::setStatus,
        onFavoriteToggle = viewModel::toggleFavorite,
        onProposeToGroup = viewModel::proposeToGroup,
        onRetryGroupSection = viewModel::retryGroupSection,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`'s pattern.
 *
 * Now eleven parameters (task 9c.6 added [groups], [onProposeToGroup], [onRetryGroupSection]),
 * well past detekt's `LongParameterList` threshold of 6; suppressed rather than bundling the eight
 * callbacks into an `Actions` holder class, which would exist for this one call site only —
 * `LibraryScreen`'s own justification for the same suppression, one screen earlier.
 *
 * [onProposeToGroup]/[onRetryGroupSection] carry NO default (`FeedScreen`'s fix-round-2 lesson,
 * BLOCKING F2, restated here before it could be rediscovered): a defaulted `= {}` here would let
 * [DetailScreen]'s own stateful call above compile even if one of these two wires were dropped from
 * it, with every pre-existing test still green — the exact hole that fix closed one screen over.
 */
@Suppress("LongParameterList")
@Composable
internal fun DetailScreen(
    state: DetailUiState,
    groups: List<Group>,
    onRetry: () -> Unit,
    onAddToLibrary: () -> Unit,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
    onProgressChange: (Int) -> Unit,
    onStatusSelected: (UserMediaStatus) -> Unit,
    onFavoriteToggle: () -> Unit,
    onProposeToGroup: (String) -> Unit,
    onRetryGroupSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is DetailUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            is DetailUiState.Error ->
                ErrorState(
                    message = stringResource(R.string.detail_error_message),
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            is DetailUiState.Success ->
                DetailContent(
                    success = state,
                    groups = groups,
                    onAddToLibrary = onAddToLibrary,
                    onScoreSelected = onScoreSelected,
                    onScoreCleared = onScoreCleared,
                    onProgressChange = onProgressChange,
                    onStatusSelected = onStatusSelected,
                    onFavoriteToggle = onFavoriteToggle,
                    onProposeToGroup = onProposeToGroup,
                    onRetryGroupSection = onRetryGroupSection,
                )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DetailContent(
    success: DetailUiState.Success,
    groups: List<Group>,
    onAddToLibrary: () -> Unit,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
    onProgressChange: (Int) -> Unit,
    onStatusSelected: (UserMediaStatus) -> Unit,
    onFavoriteToggle: () -> Unit,
    onProposeToGroup: (String) -> Unit,
    onRetryGroupSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (media, entry) = success.data
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
    ) {
        MediaHeader(media = media)
        // entry == null is the normal "not in your library" state (decision C-D), never an error
        // — the only branch here is which controls that puts on screen, Add versus Score/
        // Progress/Status/Favourite.
        if (entry == null) {
            AddSection(
                saving = success.saving,
                actionError = success.actionError as? DetailActionError.Add,
                onAddToLibrary = onAddToLibrary,
            )
        } else {
            EditSection(
                entry = entry,
                saving = success.saving,
                actionError = success.actionError as? DetailActionError.Edit,
                onScoreSelected = onScoreSelected,
                onScoreCleared = onScoreCleared,
                onProgressChange = onProgressChange,
                onStatusSelected = onStatusSelected,
                onFavoriteToggle = onFavoriteToggle,
            )
        }
        // Decision C-S: a failed group section (or a failed propose) leaves everything above it —
        // the title, the Add/Edit controls, saving/actionError — fully usable. GroupSection is
        // rendered unconditionally here; it decides internally whether it has anything to show at
        // all (its own KDoc on the Absent-and-no-groups early return).
        GroupSection(
            groupSection = success.groupSection,
            groups = groups,
            proposing = success.proposing,
            proposeError = success.proposeError,
            onProposeToGroup = onProposeToGroup,
            onRetry = onRetryGroupSection,
        )
    }
}

/** Cover, title, year/genres and the airing countdown — the same regardless of library state. */
@Composable
private fun MediaHeader(
    media: Media,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(PosterWidth))
        Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
            Text(text = media.title, style = MaterialTheme.typography.headlineSmall)
            val subtitle =
                listOfNotNull(media.year?.toString(), media.genres.takeIf { it.isNotEmpty() }?.joinToString())
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle.joinToString(separator = " · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CountdownBadge(daysUntil = media.daysUntilNextEpisode)
        }
    }
}

/** `entry == null`: one primary action. [actionError] is `add()`'s own failure, never a load failure. */
@Composable
private fun AddSection(
    saving: Boolean,
    actionError: DetailActionError.Add?,
    onAddToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
        Button(onClick = onAddToLibrary, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.detail_add_button))
        }
        // add() may have already succeeded server-side even though this call threw
        // (LibraryRepositoryImpl.add's post-add refresh() can fail independently of the POST —
        // task 9a.5's carried-forward review note), so this copy is worded to not claim the add
        // failed outright, and Add stays safe to tap again either way (the endpoint is idempotent).
        if (actionError != null) {
            Text(
                text = stringResource(R.string.detail_add_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** `entry != null`: score, progress, status and favourite, each sending only the field it edits. */
@Suppress("LongParameterList")
@Composable
private fun EditSection(
    entry: LibraryEntry,
    saving: Boolean,
    actionError: DetailActionError.Edit?,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
    onProgressChange: (Int) -> Unit,
    onStatusSelected: (UserMediaStatus) -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        ScoreEditor(
            score = entry.score,
            saving = saving,
            onScoreSelected = onScoreSelected,
            onScoreCleared = onScoreCleared,
        )
        ProgressStepper(progress = entry.progress, saving = saving, onProgressChange = onProgressChange)
        StatusSelector(selected = entry.status, saving = saving, onStatusSelected = onStatusSelected)
        FilterChip(
            selected = entry.favorite,
            onClick = onFavoriteToggle,
            enabled = !saving,
            label = { Text(text = stringResource(R.string.detail_favorite_label)) },
        )
        if (actionError != null) {
            Text(
                text = stringResource(R.string.detail_edit_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Tapping the chip opens a menu of the same half-point values the server accepts, plus "clear". */
@Composable
private fun ScoreEditor(
    score: BigDecimal?,
    saving: Boolean,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ScoreChip(score = score, modifier = Modifier.clickable(enabled = !saving) { menuExpanded = true })
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            ScoreOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option.toPlainString()) },
                    onClick = {
                        menuExpanded = false
                        onScoreSelected(option)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.detail_score_clear)) },
                onClick = {
                    menuExpanded = false
                    onScoreCleared()
                },
            )
        }
    }
}

@Composable
private fun ProgressStepper(
    progress: Int,
    saving: Boolean,
    onProgressChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        TextButton(
            onClick = { onProgressChange((progress - 1).coerceAtLeast(minimumValue = 0)) },
            enabled = !saving && progress > 0,
        ) {
            Text(text = stringResource(R.string.detail_progress_decrease))
        }
        Text(text = stringResource(R.string.detail_progress_label, progress))
        TextButton(onClick = { onProgressChange(progress + 1) }, enabled = !saving) {
            Text(text = stringResource(R.string.detail_progress_increase))
        }
    }
}

/**
 * [StatusTab] — the standalone `FilterChip` export, not
 * [com.anarky.showtrack.core.designsystem.component.StatusTabRow] — because there is no "All"
 * here: an entry's status is always exactly one of the five values, never the library screen's
 * filter-only null case.
 */
@Composable
private fun StatusSelector(
    selected: UserMediaStatus,
    saving: Boolean,
    onStatusSelected: (UserMediaStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        UserMediaStatus.entries.forEach { status ->
            StatusTab(
                status = status,
                selected = status == selected,
                onClick = { onStatusSelected(status) },
                enabled = !saving,
            )
        }
    }
}
