package com.anarky.showtrack.feature.groups

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.WatchlistEntry

/**
 * The shared watchlist's own confirmation dialog, pulled into its own file purely to keep
 * `GroupDetailScreen.kt` under detekt's `TooManyFunctions` threshold — `GroupDetailDialogs.kt`'s own
 * precedent for the identical split.
 *
 * [RemoveWatchlistEntryDialog] reuses [ConfirmActionDialog] (`GroupDetailDialogs.kt`, made
 * `internal` for exactly this reuse — see its own KDoc) rather than a near-duplicate `AlertDialog`,
 * [RemoveMemberDialog]'s identical shape one resource over: any member may remove any entry (design
 * §5.3), so [target] names WHICH row this confirms, exactly as [RemoveMemberDialog]'s own
 * `username` parameter does for a member row.
 *
 * **Fix round 1 removed `ProposeTitleDialog`/`ProposeDialogHost`.** Proposing a title needs a real
 * title picker, which needs a persisted `mediaId` no search result carries (decision C-N) — the
 * ruling that resolved this moved "propose to a group" to `:feature:detail` (task 9c.6), where a
 * real `mediaId` already exists (Detail is reached only after a title is in the proposer's own
 * library). Shipping a raw-media-id text field here was worse than not offering the action yet.
 */
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
