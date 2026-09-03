package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.WatchlistEntry

/**
 * The shared watchlist's own two dialogs (task 9c.3), pulled into their own file purely to keep
 * `GroupDetailScreen.kt` under detekt's `TooManyFunctions` threshold — `GroupDetailDialogs.kt`'s own
 * precedent for the identical split.
 *
 * [ProposeTitleDialog] is [CreateGroupDialog]/[JoinGroupDialog]'s shape (`GroupsDialogs.kt`): the
 * one free-text form on this screen, since `GroupRepository.proposeTitle(groupId, mediaId)` takes a
 * raw title id and `:feature:groups` has no title-PICKER of its own to offer instead — architecture
 * rule 1 forbids depending on `:feature:search` for one, and `WatchlistEntry.mediaId` (task 9c.0)
 * exists precisely so a future task CAN wire a proper picker (or tap-to-detail) through
 * `:core:navigation` without a data-layer change; this task's own file list does not include
 * `GroupsNavigation.kt`, so that wiring is deliberately left for later, not attempted here.
 *
 * [RemoveWatchlistEntryDialog] reuses [ConfirmActionDialog] (`GroupDetailDialogs.kt`, made
 * `internal` for exactly this reuse — see its own KDoc) rather than a near-duplicate `AlertDialog`,
 * [RemoveMemberDialog]'s identical shape one resource over: any member may remove any entry (design
 * §5.3), so [target] names WHICH row this confirms, exactly as [RemoveMemberDialog]'s own
 * `username` parameter does for a member row.
 */
@Composable
internal fun ProposeTitleDialog(
    submitting: Boolean,
    error: GroupFailure?,
    onPropose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mediaId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.groups_watchlist_propose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                Text(
                    text = stringResource(R.string.groups_watchlist_propose_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = mediaId,
                    onValueChange = { mediaId = it },
                    label = { Text(text = stringResource(R.string.groups_watchlist_propose_media_id_label)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = stringResource(it.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // Decision C-S: the error is cleared the moment GroupDetailViewModel.proposeTitle
            // launches a retry, so this can safely re-invoke onPropose straight away —
            // CreateGroupDialog's identical reasoning for its own submit button.
            TextButton(onClick = { onPropose(mediaId) }, enabled = !submitting && mediaId.isNotBlank()) {
                Text(
                    text =
                        stringResource(
                            if (submitting) {
                                R.string.groups_watchlist_propose_submitting
                            } else {
                                R.string.groups_watchlist_propose_submit
                            },
                        ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = stringResource(R.string.groups_watchlist_propose_cancel))
            }
        },
    )
}

@Composable
internal fun RemoveWatchlistEntryDialog(
    title: String,
    submitting: Boolean,
    error: GroupFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmActionDialog(
        title = stringResource(R.string.groups_watchlist_remove_confirm_title, title),
        message = stringResource(R.string.groups_watchlist_remove_confirm_message),
        confirmLabel = stringResource(R.string.groups_watchlist_remove_confirm_button),
        cancelLabel = stringResource(R.string.groups_watchlist_remove_cancel),
        submitting = submitting,
        error = error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** [ProposeTitleDialog]'s own visibility guard — [RotateDialogHost]'s identical reasoning. */
@Composable
internal fun ProposeDialogHost(
    visible: Boolean,
    actionState: GroupDetailActionState,
    onPropose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        ProposeTitleDialog(
            submitting = actionState.proposing,
            error = actionState.proposeError,
            onPropose = onPropose,
            onDismiss = onDismiss,
        )
    }
}

/**
 * [RemoveWatchlistEntryDialog]'s own visibility guard — [RemoveDialogHost]'s identical reasoning,
 * visibility carried by [target] itself (non-null means "showing").
 */
@Composable
internal fun RemoveWatchlistEntryDialogHost(
    target: WatchlistEntry?,
    actionState: GroupDetailActionState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target != null) {
        RemoveWatchlistEntryDialog(
            title = target.media.title,
            submitting = actionState.removingEntryId == target.id,
            error = actionState.removeEntryError,
            onConfirm = { onConfirm(target.id) },
            onDismiss = onDismiss,
        )
    }
}
