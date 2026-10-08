package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.SpoilerReview
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Review

/**
 * The active group on this title: its name, the race track, then everyone as a list with how far
 * ahead or behind you they are. Absent when there is no active group.
 */
@Suppress("LongParameterList")
@Composable
internal fun GroupSection(
    groupSection: GroupSectionState,
    groupName: String?,
    meId: String?,
    myEntry: LibraryEntry?,
    totalEpisodes: Int?,
    episodeList: EpisodeList?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groupSection is GroupSectionState.Absent) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = groupName ?: stringResource(R.string.detail_group_heading_fallback),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when (groupSection) {
            GroupSectionState.Absent -> Unit
            GroupSectionState.Loading -> LoadingState()
            is GroupSectionState.Error ->
                ErrorState(message = stringResource(groupSection.cause.messageRes()), onRetry = onRetry)
            is GroupSectionState.Loaded -> {
                if (groupSection.isStale) {
                    StaleDataBanner(
                        onRetry = onRetry,
                        messageRes = R.string.detail_group_stale_notice,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                val members = RaceRules.withMyEntry(groupSection.progress, meId, myEntry?.progress, myEntry?.status)
                if (members.isEmpty()) {
                    EmptyState(message = stringResource(R.string.detail_group_section_empty))
                } else {
                    RaceTrack(members = members, meId = meId, total = totalEpisodes, list = episodeList)
                    val mine = members.firstOrNull { it.member.id == meId }?.progress
                    members.forEach { member ->
                        key(member.member.id) {
                            MemberRow(member = member, isMe = member.member.id == meId, mine = mine, list = episodeList)
                        }
                    }
                }
            }
        }
    }
}

/** "Reviews" with "Write a review" in the heading, then the group's reviews; yours can be edited or deleted. */
@Suppress("LongParameterList")
@Composable
internal fun ReviewsSection(
    reviews: List<Review>,
    // False while the group's reviews are loading or failed: whether you already wrote one is
    // unknown, so "Write a review" waits instead of opening a draft over an existing review.
    reviewsKnown: Boolean,
    meId: String?,
    reviewEditor: ReviewEditorState,
    deleting: Boolean,
    deleteError: GroupFailure?,
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val own = reviews.firstOrNull { it.author.id == meId }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.detail_reviews_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (reviewEditor == ReviewEditorState.Closed && own == null && reviewsKnown) {
                TextButton(onClick = actions.onOpenReviewEditor) {
                    Text(text = stringResource(R.string.detail_review_write_button))
                }
            }
        }
        if (reviewEditor is ReviewEditorState.Open) {
            ReviewEditorSection(
                reviewEditor = reviewEditor,
                onOpen = actions.onOpenReviewEditor,
                onSave = actions.onSaveReview,
                onCancel = actions.onCancelReviewEditor,
                onClearError = actions.onClearReviewError,
            )
        }
        reviews.forEach { review ->
            key(review.id) {
                ReviewItem(
                    review = review,
                    ownActions = review.id == own?.id && reviewEditor == ReviewEditorState.Closed,
                    deleting = deleting,
                    onEdit = actions.onOpenReviewEditor,
                    onDelete = { confirmDelete = true },
                )
            }
        }
        if (deleteError != null) {
            Text(
                text = stringResource(R.string.detail_review_delete_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (confirmDelete) {
        DeleteReviewDialog(
            onConfirm = {
                confirmDelete = false
                actions.onDeleteReview()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A review; yours carries Edit and Delete under it. */
@Composable
private fun ReviewItem(
    review: Review,
    ownActions: Boolean,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        SpoilerReview(review = review)
        if (ownActions) {
            Row {
                val editDescription = stringResource(R.string.detail_review_edit_description)
                val deleteDescription = stringResource(R.string.detail_review_delete_description)
                TextButton(
                    onClick = onEdit,
                    enabled = !deleting,
                    modifier = Modifier.semantics { contentDescription = editDescription },
                ) {
                    Text(text = stringResource(R.string.detail_review_edit))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !deleting,
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                ) {
                    Text(text = stringResource(R.string.detail_review_delete))
                }
            }
        }
    }
}

@Composable
private fun DeleteReviewDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.detail_review_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.detail_review_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.detail_review_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.detail_review_delete_cancel)) }
        },
    )
}

/** "Propose to a group", at the end of the screen. One group proposes at once; more opens a picker. */
@Suppress("LongParameterList")
@Composable
internal fun ProposeSection(
    groups: List<Group>,
    proposing: Boolean,
    error: GroupFailure?,
    justProposedToGroupId: String?,
    onPropose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    var showPicker by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = { if (groups.size == 1) onPropose(groups.single().id) else showPicker = true },
            enabled = !proposing,
            modifier = Modifier.fillMaxWidth(),
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
