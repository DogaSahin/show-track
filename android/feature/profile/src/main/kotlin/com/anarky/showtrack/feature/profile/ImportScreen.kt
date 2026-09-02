package com.anarky.showtrack.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.model.ImportSummary

/**
 * The AniList import screen (task 9b.6). Reached from two places `:app` stitches together
 * (architecture rule 1): `:feature:profile`'s own settings action, and `:feature:auth`'s
 * post-register onboarding — see `ImportNavigation.kt` and `AuthNavigation.kt`.
 *
 * [onFinished] fires on the skip action AND on the terminal [ImportUiState.Success] screen's own
 * "Done" button — both send the caller to the same place (`ImportNavigation.kt` maps it to
 * `LibraryRoute`), so one callback rather than two that would always be wired identically.
 */
@Composable
fun ImportScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImportScreen(
        state = state,
        onImport = viewModel::import,
        onFinished = onFinished,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be driven by a test with no ViewModel and no Hilt —
 * `AuthScreen`/`ProfileScreen`'s pattern.
 *
 * The two limitations — public profile required, one-way and permanent (architecture rule 7) —
 * are shown unconditionally, ABOVE the username field, in every [ImportUiState.Form] render: the
 * task brief requires saying them before the user types a username, not folding them into an
 * error shown only after a failed attempt.
 */
@Composable
internal fun ImportScreen(
    state: ImportUiState,
    onImport: (String) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(space = 16.dp),
    ) {
        Text(text = stringResource(R.string.import_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.import_public_requirement), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.import_one_way_notice), style = MaterialTheme.typography.bodyMedium)

        when (state) {
            is ImportUiState.Form ->
                ImportForm(
                    state = state,
                    username = username,
                    onUsernameChange = { username = it },
                    onImport = { onImport(username) },
                    onSkip = onFinished,
                )

            is ImportUiState.Success -> ImportResult(summary = state.summary, onDone = onFinished)
        }
    }
}

@Composable
private fun ImportForm(
    state: ImportUiState.Form,
    username: String,
    onUsernameChange: (String) -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(text = stringResource(R.string.import_username_label)) },
        singleLine = true,
        enabled = !state.submitting,
        modifier = Modifier.fillMaxWidth(),
    )

    // Decision C-S: the error is cleared the moment a retry launches (ImportViewModel.import sets
    // a fresh Form(submitting = true) with no error), not only on success — so this can safely
    // wire ErrorState's own retry action straight to another attempt.
    //
    // Round 1 fix (task 9b.6 fix round, M5): guarded with the SAME `isNotBlank()` the primary
    // button below enforces — `ErrorState` exposes no `enabled` to disable its own button, so an
    // unguarded `onRetry` let a user clear the username field after a failure and then tap Retry
    // straight into a guaranteed 422 for an empty username, silently bypassing the validation the
    // primary button already does. A no-op tap while blank, matching the primary button's
    // disabled state in OUTCOME even though `ErrorState`'s retry button itself stays visually
    // enabled.
    state.error?.let { error ->
        ErrorState(
            message = stringResource(error.messageRes()),
            onRetry = { if (username.isNotBlank()) onImport() },
        )
    }

    Button(
        onClick = onImport,
        enabled = !state.submitting && username.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(if (state.submitting) R.string.import_submitting else R.string.import_submit),
        )
    }

    // The brief's "expect it to take seconds, and show progress accordingly" (M3, round 1): the
    // swapped button label alone was easy to miss at a glance mid-import, on a synchronous call
    // that genuinely takes seconds. A visible spinner is also what makes the `enabled =
    // !state.submitting` guard above matter to a real user, not just to a test — without it,
    // nothing on screen visibly discourages a second tap while the first import is still running,
    // which would race two full AniList imports against each other server-side.
    if (state.submitting) {
        CircularProgressIndicator(modifier = Modifier.size(ImportSpinnerSize))
    }

    // The onboarding entry point must be skippable (task brief) — a user who declines lands in
    // the library, never stuck on a form they cannot get past. Reached from Profile too, where
    // this is simply "I changed my mind" rather than a dead end: system Back already returns
    // there, since ImportRoute is an ordinary pushed destination on that path.
    TextButton(onClick = onSkip) {
        Text(text = stringResource(R.string.import_skip))
    }
}

/** `SearchScreen`'s `AddingSpinnerSize` naming convention, for the identical kind of inline spinner. */
private val ImportSpinnerSize = 20.dp

/**
 * The terminal state. [ImportSummary.truncated] renders as its OWN, separate notice rather than
 * folding into the three counts — decision 4-L exists precisely so a truncated import is
 * distinguishable from a complete one, and a count alone (e.g. "40 imported") cannot carry that;
 * two imports with identical counts can differ only in whether one of them was cut short.
 */
@Composable
private fun ImportResult(
    summary: ImportSummary,
    onDone: () -> Unit,
) {
    Text(text = stringResource(R.string.import_success_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = pluralStringResource(R.plurals.import_result_imported, summary.imported, summary.imported),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = pluralStringResource(R.plurals.import_result_skipped, summary.skipped, summary.skipped),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = pluralStringResource(R.plurals.import_result_failed, summary.failed, summary.failed),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (summary.truncated) {
        Text(
            text = stringResource(R.string.import_truncated_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.import_done))
    }
}

/** The one place an [ImportError] becomes copy — `AuthScreen`'s identical `AuthError.messageRes()` pattern. */
private fun ImportError.messageRes(): Int =
    when (this) {
        ImportError.ProfileNotPublic -> R.string.import_error_not_public
        ImportError.InvalidUsername -> R.string.import_error_invalid_username
        ImportError.UpstreamUnavailable -> R.string.import_error_upstream_unavailable
        ImportError.Offline -> R.string.import_error_offline
        ImportError.Unknown -> R.string.import_error_unknown
    }
