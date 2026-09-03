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

/**
 * [CreateGroupDialog] and [JoinGroupDialog] — pulled out of `GroupsScreen.kt` into their own file
 * purely to keep that file under detekt's `TooManyFunctions` threshold; no behaviour moved with
 * them that the caller could observe differently. `GroupsScreen.kt`'s own KDoc on the stateless
 * `GroupsScreen` overload documents WHY these dialogs own their own text field state rather than
 * `GroupsUiState` — that reasoning stays there, not duplicated here.
 */
@Composable
internal fun CreateGroupDialog(
    submitting: Boolean,
    error: GroupFailure?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.groups_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.groups_create_name_label)) },
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
            // Decision C-S: the error is cleared the moment GroupsViewModel.createGroup launches a
            // retry, so this can safely re-invoke onCreate straight away — mirroring ImportForm's
            // primary button guard.
            TextButton(onClick = { onCreate(name) }, enabled = !submitting && name.isNotBlank()) {
                Text(
                    text =
                        stringResource(
                            if (submitting) R.string.groups_create_submitting else R.string.groups_create_submit,
                        ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = stringResource(R.string.groups_create_cancel))
            }
        },
    )
}

/**
 * [code] is this dialog's own `remember`ed draft — never reset on a failed [onJoin], which is
 * what makes "a failed join keeps the typed code" true (`GroupsScreenTest` pins this directly).
 */
@Composable
internal fun JoinGroupDialog(
    submitting: Boolean,
    error: GroupFailure?,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.groups_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(text = stringResource(R.string.groups_join_code_label)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                // GroupFailure.InvalidInviteCode (fix round 2; renamed from BadRequest in fix
                // round 3 — see its own KDoc) is what a bad or expired invite code surfaces as —
                // GroupRepository.joinGroup's own KDoc — a dedicated case, not the generic
                // GroupFailure.Unknown a 500 or an expired session ALSO produces (round 1's
                // original shape conflated the two). messageRes() below renders it distinctly on
                // its own, so no caller override is needed here any more.
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
            TextButton(onClick = { onJoin(code) }, enabled = !submitting && code.isNotBlank()) {
                Text(
                    text =
                        stringResource(
                            if (submitting) R.string.groups_join_submitting else R.string.groups_join_submit,
                        ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = stringResource(R.string.groups_join_cancel))
            }
        },
    )
}

/**
 * The one place a [GroupFailure] becomes copy — `ImportScreen`'s identical `ImportError.messageRes()`
 * pattern. Three dedicated cases ([GroupFailure.Network], [GroupFailure.InvalidInviteCode],
 * [GroupFailure.NotAMember] — the last added round 1 review, minor 4, for
 * [GroupDetailScreen]/`GroupDetailDialogs.kt`'s benefit, since it is the single most likely
 * non-network failure there) and one generic fallback for the rest: `NotPermitted`, `NoSuchTitle`,
 * `NoSuchEntry`, [GroupFailure.AlreadyReviewed] and [GroupFailure.Unknown] describe failures from
 * feed/watchlist/review endpoints `GroupsScreen`'s own create/join forms never call, or genuinely
 * unexpected ones (a 500, an expired session) — creating or joining a group cannot produce the
 * former, and the latter has no more specific story than "something went wrong" — so a dedicated
 * string for either would either name a case that form can never reach, or claim specificity the
 * client does not have. [GroupFailure.Unknown.cause] is deliberately not read here (that field is
 * for logging only — see its own KDoc): an `HttpException`'s message is the raw HTTP status line,
 * never fit for user-facing copy.
 *
 * **Fix round 2:** [GroupFailure.InvalidInviteCode] replaces round 1's `unknownRes`
 * caller-override parameter — round 1 discriminated "bad invite code" from the generic case by
 * asking the CALLER to say which [GroupFailure.Unknown] meant that, but every [GroupFailure.Unknown]
 * looked identical from here, so a 500 or an expired session on the SAME form got the "that code
 * might be wrong" copy too (measured in review). [GroupFailure.InvalidInviteCode] is a real,
 * dedicated TYPE now — see its own KDoc, including fix round 3's rename from `BadRequest` — so the
 * discrimination happens at `:core:data`'s boundary, where the actual HTTP status is visible, not
 * by a caller guessing which `Unknown` it was.
 *
 * `internal`, not `private`: `GroupsScreen.kt`'s `GroupsContent` also needs it for
 * [GroupsUiState.Error]'s own message, and this file is where the mapping lives (split out to keep
 * `GroupsScreen.kt` under detekt's `TooManyFunctions` threshold — see this file's own KDoc).
 *
 * **Task 9c.3 gives [GroupFailure.NoSuchEntry] its own copy**, the identical round-1 reasoning
 * [GroupFailure.NotAMember] already got: it fell into the generic fallback while nothing on screen
 * could ever produce it (this file's own prior note said so explicitly). That stopped being true
 * the moment `RemoveWatchlistEntryDialog` could reach it, and it names something the generic
 * "something went wrong" copy would actively misdescribe: [GroupFailure.NoSuchEntry] means the row
 * is already gone, not that anything is broken — see that case's own KDoc for the membership-race
 * shadow this mapping deliberately still accepts.
 *
 * **[GroupFailure.NoSuchTitle] stays in the generic fallback below** (fix round 1 reverted task
 * 9c.3's own dedicated case): it was added for `ProposeTitleDialog`, which fix round 1 removed from
 * this screen entirely — see [GroupDetailActionState]'s own KDoc for why proposing moved to
 * `:feature:detail` (task 9c.6). Nothing in `:feature:groups` can produce this failure any more, so
 * a dedicated branch for it here would name a case this module can no longer reach.
 */
internal fun GroupFailure.messageRes(): Int =
    when (this) {
        GroupFailure.Network -> R.string.groups_error_network
        GroupFailure.InvalidInviteCode -> R.string.groups_join_error_bad_code
        // Round 1 review, minor 4: a dedicated case, not the generic fallback below — NotAMember
        // is the single most likely non-network failure on the group detail screen (removed from
        // the group elsewhere, or the group is gone), and "something went wrong, try again" is
        // actively misleading for a 404 that will answer identically on every retry.
        GroupFailure.NotAMember -> R.string.groups_error_not_a_member
        GroupFailure.NoSuchEntry -> R.string.groups_watchlist_error_no_such_entry
        GroupFailure.NotPermitted,
        GroupFailure.NoSuchTitle,
        is GroupFailure.AlreadyReviewed,
        is GroupFailure.Unknown,
        -> R.string.groups_error_unknown
    }
