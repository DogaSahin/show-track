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
                // unknownRes = groups_join_error_bad_code (fix round 1, small item 3): a bad or
                // expired invite code is a 400 the backend deliberately does not distinguish
                // (GroupRepository.joinGroup's own KDoc), so it surfaces as GroupFailure.Unknown —
                // the SAME case every other Unknown failure hits. Left at the generic "something
                // went wrong" copy, the single most common outcome of THIS form would never tell
                // the user their code might be the problem. Says it MAY be wrong or expired,
                // never WHICH — the server does not distinguish them either.
                error?.let {
                    Text(
                        text = stringResource(it.messageRes(unknownRes = R.string.groups_join_error_bad_code)),
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
 * pattern. Every case besides [GroupFailure.Network] and [GroupFailure.Unknown] folds to the same
 * generic message: the remaining four (`NotAMember`, `NotPermitted`, `NoSuchTitle`, `NoSuchEntry`)
 * plus [GroupFailure.AlreadyReviewed] describe failures from feed/watchlist/review endpoints this
 * screen never calls — creating or joining a group cannot produce them — so a dedicated string for
 * each would name a case this form can never actually reach. [GroupFailure.Unknown.cause] is
 * deliberately not read here (that field is for logging only — see its own KDoc): an
 * `HttpException`'s message is the raw HTTP status line, never fit for user-facing copy.
 *
 * [unknownRes] is decision C-S's "the sink is a parameter of the guard helper chosen by the
 * caller" applied to copy, not just control flow — the same shape [GroupRepositoryImpl]'s own
 * `guarded(notFound = …)` uses in `:core:data`. Added in fix round 1: [GroupFailure.Unknown] is
 * NOT one generic case for every caller — a bad or expired invite code surfaces as exactly this
 * case (see [JoinGroupDialog]'s own call site), and that is common and specific enough to deserve
 * its own copy, while `CreateGroupDialog` and [GroupsUiState.Error]'s rendering in `GroupsScreen.kt`
 * have no comparably specific story for it and keep the generic default.
 *
 * `internal`, not `private`: `GroupsScreen.kt`'s `GroupsContent` also needs it for
 * [GroupsUiState.Error]'s own message, and this file is where the mapping lives (split out to keep
 * `GroupsScreen.kt` under detekt's `TooManyFunctions` threshold — see this file's own KDoc).
 */
internal fun GroupFailure.messageRes(unknownRes: Int = R.string.groups_error_unknown): Int =
    when (this) {
        GroupFailure.Network -> R.string.groups_error_network
        GroupFailure.NotAMember,
        GroupFailure.NotPermitted,
        GroupFailure.NoSuchTitle,
        GroupFailure.NoSuchEntry,
        is GroupFailure.AlreadyReviewed,
        -> R.string.groups_error_unknown
        is GroupFailure.Unknown -> unknownRes
    }
