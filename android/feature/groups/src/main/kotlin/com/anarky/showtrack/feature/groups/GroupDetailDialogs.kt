package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole

/**
 * The three owner/member confirmation dialogs [GroupDetailScreen] opens — rotate, leave, remove —
 * pulled into their own file purely to keep that file under detekt's `TooManyFunctions` threshold,
 * `GroupsDialogs.kt`'s own precedent for the identical split on `GroupsScreen.kt`.
 *
 * All three share [ConfirmActionDialog]'s shape rather than each hand-rolling an `AlertDialog`:
 * unlike [CreateGroupDialog]/[JoinGroupDialog] (`GroupsDialogs.kt`), none of these three collects
 * free-text input — every one of them is "are you sure", with its own title/message/button copy and
 * its own [GroupDetailActionState] channel (decision C-S) for `submitting`/`error`.
 *
 * `@Suppress("LongParameterList")`: eight parameters is what "title, message, both button labels,
 * submitting, error, both callbacks" genuinely needs for a generic confirm dialog — splitting it
 * further would mean re-introducing the three near-duplicate `AlertDialog`s this function exists
 * to replace.
 */
@Suppress("LongParameterList")
@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    submitting: Boolean,
    error: GroupFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
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
            // Decision C-S: the caller clears its own error the moment the retry launches, so
            // this can safely re-invoke onConfirm straight away — CreateGroupDialog's identical
            // reasoning for its own submit button.
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = cancelLabel)
            }
        },
    )
}

/** Owner only (E-F) — [GroupDetailScreen] never opens this for a non-owner in the first place. */
@Composable
internal fun RotateInviteDialog(
    submitting: Boolean,
    error: GroupFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmActionDialog(
        title = stringResource(R.string.groups_detail_rotate_confirm_title),
        message = stringResource(R.string.groups_detail_rotate_confirm_message),
        confirmLabel = stringResource(R.string.groups_detail_rotate_confirm_button),
        cancelLabel = stringResource(R.string.groups_detail_rotate_cancel),
        submitting = submitting,
        error = error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Offered to every member, owner included — `GroupDetailViewModel.leaveGroup`'s own KDoc. */
@Composable
internal fun LeaveGroupDialog(
    submitting: Boolean,
    error: GroupFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmActionDialog(
        title = stringResource(R.string.groups_detail_leave_confirm_title),
        message = stringResource(R.string.groups_detail_leave_confirm_message),
        confirmLabel = stringResource(R.string.groups_detail_leave_confirm_button),
        cancelLabel = stringResource(R.string.groups_detail_leave_cancel),
        submitting = submitting,
        error = error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Owner only (E-F), and [GroupDetailScreen] never opens this for the signed-in member's OWN row —
 * [username] names WHO is about to be removed, since this is the one confirm dialog of the three
 * that is not about the caller's own membership.
 */
@Composable
internal fun RemoveMemberDialog(
    username: String,
    submitting: Boolean,
    error: GroupFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmActionDialog(
        title = stringResource(R.string.groups_detail_remove_confirm_title, username),
        message = stringResource(R.string.groups_detail_remove_confirm_message),
        confirmLabel = stringResource(R.string.groups_detail_remove_confirm_button),
        cancelLabel = stringResource(R.string.groups_detail_remove_cancel),
        submitting = submitting,
        error = error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** [GroupRole] -> its display label — [GroupDetailScreen]'s member rows, each owner/member badge. */
internal fun GroupRole.labelRes(): Int =
    when (this) {
        GroupRole.OWNER -> R.string.groups_detail_role_owner
        GroupRole.MEMBER -> R.string.groups_detail_role_member
    }

/**
 * [RotateInviteDialog]'s own visibility guard — pulled out of `GroupDetailScreen.kt` (round 1
 * review) to keep that file's own function count under detekt's `TooManyFunctions` threshold; no
 * behaviour moved with it that a caller could observe differently.
 */
@Composable
internal fun RotateDialogHost(
    visible: Boolean,
    actionState: GroupDetailActionState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        RotateInviteDialog(
            submitting = actionState.rotating,
            error = actionState.rotateError,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** [LeaveGroupDialog]'s own visibility guard — [RotateDialogHost]'s identical reasoning. */
@Composable
internal fun LeaveDialogHost(
    visible: Boolean,
    actionState: GroupDetailActionState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        LeaveGroupDialog(
            submitting = actionState.leaving,
            error = actionState.leaveError,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * [RemoveMemberDialog]'s own visibility guard — [RotateDialogHost]'s identical reasoning, except
 * visibility is carried by [target] itself (non-null means "showing"), matching
 * `GroupDetailScreen`'s own `pendingRemoveTarget?.let { }` this replaces.
 */
@Composable
internal fun RemoveDialogHost(
    target: GroupMember?,
    actionState: GroupDetailActionState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target != null) {
        RemoveMemberDialog(
            username = target.username,
            submitting = actionState.removingUserId == target.userId,
            error = actionState.removeError,
            onConfirm = { onConfirm(target.userId) },
            onDismiss = onDismiss,
        )
    }
}
