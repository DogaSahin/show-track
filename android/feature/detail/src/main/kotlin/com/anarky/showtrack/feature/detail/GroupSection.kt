package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.SpoilerReview
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.MemberProgress

/**
 * The group section on Detail (task 9c.6, decision E-J, design doc §3.5): everyone's progress on
 * this title within the ACTIVE group, plus that group's reviews with spoilers collapsed until
 * tapped, plus a "propose this title to a group" door — moved here from `:feature:groups` in fix
 * round 1 of task 9c.3 (see [DetailUiState.Success.proposing]'s own KDoc): a search-backed picker
 * needs a persisted `mediaId`, which [com.anarky.showtrack.core.model.MediaSummary] deliberately
 * does not carry (decision C-N — a search result writes nothing, so no row exists to have an id),
 * and Detail is the one surface reliably holding one (a title only reaches Detail with a real
 * `mediaId` once it is already in the proposer's OWN library).
 *
 * [groupSection] is [GroupSectionState.Absent] and [groups] is empty TOGETHER, always — both come
 * from the SAME upstream fact ("the active-group `StateFlow` resolved to zero groups, or has not
 * resolved yet"), never independently. The early `return` below reads that invariant directly
 * rather than re-deriving it, so this section renders nothing at all rather than an empty header
 * with no content underneath it.
 *
 * **The propose control renders directly, with no picker, for exactly one group** — [GroupSwitcher]'s
 * own "a picker over one option is noise" reasoning (E-K), extended from a tab row to a form
 * control: a reader in exactly one group has no meaningful CHOICE to make, only a confirmation to
 * skip. Two or more groups opens [GroupPickerDialog].
 */
@Suppress("LongParameterList")
@Composable
internal fun GroupSection(
    groupSection: GroupSectionState,
    groups: List<Group>,
    proposing: Boolean,
    proposeError: GroupFailure?,
    justProposedToGroupId: String?,
    onProposeToGroup: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groupSection is GroupSectionState.Absent && groups.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        Text(text = stringResource(R.string.detail_group_section_title), style = MaterialTheme.typography.titleMedium)
        if (groups.isNotEmpty()) {
            ProposeToGroupControl(
                groups = groups,
                proposing = proposing,
                error = proposeError,
                justProposedToGroupId = justProposedToGroupId,
                onPropose = onProposeToGroup,
            )
        }
        GroupSectionBody(groupSection = groupSection, onRetry = onRetry)
    }
}

@Composable
private fun GroupSectionBody(
    groupSection: GroupSectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (groupSection) {
        // Nothing else to render: either there is no active group (handled by GroupSection's own
        // early return when groups is also empty), or the active group has not resolved yet — in
        // which case this simply waits for the next recomposition rather than showing a spinner
        // for a fetch that has not been asked for.
        GroupSectionState.Absent -> Unit
        GroupSectionState.Loading -> LoadingState(modifier = modifier)
        is GroupSectionState.Error ->
            ErrorState(
                message = stringResource(groupSection.cause.messageRes()),
                onRetry = onRetry,
                modifier = modifier,
            )
        is GroupSectionState.Loaded ->
            GroupSectionContent(
                loaded = groupSection,
                onRetry = onRetry,
                modifier = modifier,
            )
    }
}

/** §9.12's own acceptance criterion: both lists empty is a real, successful outcome, not a broken section. */
@Composable
private fun GroupSectionContent(
    loaded: GroupSectionState.Loaded,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        if (loaded.isStale) {
            StaleDataBanner(onRetry = onRetry, messageRes = R.string.detail_group_stale_notice)
        }
        if (loaded.progress.isEmpty() && loaded.reviews.isEmpty()) {
            EmptyState(message = stringResource(R.string.detail_group_section_empty))
        } else {
            if (loaded.progress.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
                    loaded.progress.forEach { row -> ProgressRow(progress = row) }
                }
            }
            if (loaded.reviews.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                    // key(review.id): a PLAIN Column.forEach (not LazyColumn) still uses
                    // positional slot reuse across a recomposition — a reload that replaces
                    // review N at this position with a DIFFERENT review must not inherit whatever
                    // composition state (SpoilerReview's own `revealed`) the old occupant of this
                    // slot left behind. REDUNDANT with SpoilerReview's own
                    // `rememberSaveable(review.id)` brace, measured, not assumed: mutating either
                    // ONE of the two alone leaves `DetailScreenTest`'s own swap test green — each
                    // is independently sufficient — and only removing BOTH at once reddens it.
                    // Kept anyway: this call site is the one place that can see the whole LIST
                    // (SpoilerReview itself only ever sees one [Review] at a time), so it is the
                    // right place to state the invariant even though today it is provably not the
                    // only thing enforcing it.
                    loaded.reviews.forEach { review -> key(review.id) { SpoilerReview(review = review) } }
                }
            }
        }
    }
}

/**
 * One member's status + progress on this title — [MemberProgress]'s own nested-actor shape, the
 * feed's identical attribution.
 */
@Composable
private fun ProgressRow(progress: MemberProgress) {
    Text(
        text =
            stringResource(
                R.string.detail_group_progress_row,
                progress.member.username,
                progress.status.label(),
                progress.progress,
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * The "propose to a group" button, its own error line, and — for two or more groups — the picker
 * dialog. [showPicker] is plain composable state: purely a "which dialog is open" fact, never
 * anything [DetailViewModel] needs to know about (decision C-S's own boundary — a ViewModel owns
 * OPERATION state, not which confirmation UI a screen currently shows).
 */
@Composable
private fun ProposeToGroupControl(
    groups: List<Group>,
    proposing: Boolean,
    error: GroupFailure?,
    justProposedToGroupId: String?,
    onPropose: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
        TextButton(
            onClick = { if (groups.size == 1) onPropose(groups.single().id) else showPicker = true },
            enabled = !proposing,
        ) {
            Text(text = stringResource(R.string.detail_group_propose_button))
        }
        if (error != null) {
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The ONLY feedback a single-group propose gets: groups.size == 1 opens no picker and no
        // confirmation dialog, so without this line a successful propose there is indistinguishable
        // from a dead button (fix round 1, coordinator finding 4). Resolved to the group's own NAME
        // (server data, decision C-E is unaffected — GroupSwitcher's identical `group.name` precedent),
        // falling back to a generic string on the defensive case the id names no group in [groups].
        if (justProposedToGroupId != null) {
            val groupName = groups.firstOrNull { group -> group.id == justProposedToGroupId }?.name
            Text(
                text =
                    groupName?.let { stringResource(R.string.detail_group_propose_success, it) }
                        ?: stringResource(R.string.detail_group_propose_success_generic),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (showPicker) {
        GroupPickerDialog(
            groups = groups,
            onGroupSelected = { groupId ->
                showPicker = false
                onPropose(groupId)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * One row per group; tapping a row both chooses it AND confirms — there is nothing left for a
 * separate "confirm" button to do, so [AlertDialog.confirmButton] is deliberately empty rather than
 * a redundant control that would confirm nothing in particular.
 */
@Composable
private fun GroupPickerDialog(
    groups: List<Group>,
    onGroupSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.detail_group_propose_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
                groups.forEach { group ->
                    TextButton(onClick = { onGroupSelected(group.id) }) {
                        Text(text = group.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.detail_group_propose_picker_cancel))
            }
        },
    )
}

/**
 * The one place a [GroupFailure] becomes copy in this module — `FeedScreen.kt`'s
 * `GroupFailure.messageRes()` pattern, re-implemented here rather than reused: that function is
 * `internal` to `:feature:feed`, and architecture rule 1 forbids `:feature:detail` depending on
 * another feature module for it either way.
 *
 * Exhaustive over all EIGHT [GroupFailure] cases, no `else`, so a ninth case added later fails this
 * file to COMPILE rather than silently falling through to a generic message nobody chose.
 *
 * [GroupFailure.NoSuchTitle] gets its OWN copy (this task's carried-forward instruction: folding it
 * into the generic fallback would misdescribe it the way an earlier round's [GroupFailure.NotAMember]
 * fallback did) — it is [GroupRepository.proposeTitle]'s 404, and per that failure's own KDoc it
 * ALSO covers the shadowed "you were removed from the group" case for that one endpoint, since
 * `guarded(notFound = GroupFailure.NoSuchTitle)` cannot distinguish the two by status code alone;
 * "propose failed" copy stays honest either way. [GroupFailure.NotAMember] gets its own copy too —
 * `GroupRepository.progress`/`.reviews`'s 404, and the single most likely non-network failure this
 * section's OWN fetch can produce (an owner removes this account from the group while its detail
 * screen is open). Every other case ([GroupFailure.NotPermitted], [GroupFailure.NoSuchEntry],
 * [GroupFailure.AlreadyReviewed], [GroupFailure.InvalidInviteCode], [GroupFailure.Unknown]) describes
 * an endpoint nothing in this file calls, so a dedicated string for any of them would name a case
 * this section can never actually reach. [GroupFailure.Unknown.cause] is never read here — logging only.
 */
internal fun GroupFailure.messageRes(): Int =
    when (this) {
        GroupFailure.Network -> R.string.detail_group_error_network
        GroupFailure.NotAMember -> R.string.detail_group_error_not_a_member
        GroupFailure.NoSuchTitle -> R.string.detail_group_propose_error_no_such_title
        GroupFailure.NotPermitted,
        GroupFailure.NoSuchEntry,
        is GroupFailure.AlreadyReviewed,
        GroupFailure.InvalidInviteCode,
        is GroupFailure.Unknown,
        -> R.string.detail_group_error_unknown
    }
