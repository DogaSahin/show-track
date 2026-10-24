package com.anarky.showtrack.update

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.anarky.showtrack.R
import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState

/**
 * The optional-update prompt, above the signed-in screens. [progress] non-null means a download is
 * running: the buttons give way to a progress bar so a second tap cannot start a second download
 * (the ViewModel ignores one anyway). [failed] swaps Install for Retry.
 *
 * `LongParameterList` is suppressed: five inputs plus the conventional modifier, and bundling
 * them would only move the list into a holder class.
 */
@Suppress("LongParameterList")
@Composable
fun UpdateBanner(
    release: AppRelease,
    progress: Float?,
    failed: Boolean,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.update_available_title, release.versionName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (release.notes.isNotBlank()) {
                Text(
                    text = release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null) {
                DownloadProgress(progress = progress)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (failed) {
                        Text(
                            text = stringResource(R.string.update_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                    TextButton(onClick = onInstall) {
                        Text(stringResource(if (failed) R.string.update_retry else R.string.update_install))
                    }
                }
            }
        }
    }
}

/**
 * Shown instead of the app when the installed build is below the server's minimum. There is no
 * Later: the only ways forward are the update and signing out (which stays possible here).
 *
 * `LongParameterList` is suppressed for the same reason as [UpdateBanner].
 */
@Suppress("LongParameterList")
@Composable
fun UpdateRequiredScreen(
    release: AppRelease,
    progress: Float?,
    failed: Boolean,
    signOutFailed: Boolean,
    onInstall: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Surface rather than a bare Column: it is drawn over the app, so it must paint the whole
    // area and take the touches that would otherwise reach the screen underneath.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.update_required_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.update_required_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.update_version, release.versionName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                DownloadProgress(progress = progress)
            } else {
                if (failed) ErrorLine(R.string.update_failed)
                Button(onClick = onInstall) {
                    Text(stringResource(if (failed) R.string.update_retry else R.string.update_install))
                }
            }
            if (signOutFailed) ErrorLine(R.string.update_sign_out_failed)
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.update_sign_out)) }
        }
    }
}

/**
 * Why the next tap opens system settings, shown before the trip rather than dropping the user on
 * an unexplained settings page. The install resumes on return if the switch was turned on.
 */
@Composable
fun InstallPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_permission_title)) },
        text = { Text(stringResource(R.string.update_permission_body)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.update_permission_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_permission_cancel)) }
        },
    )
}

@Composable
private fun ErrorLine(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun DownloadProgress(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** Where the session stands, for [UpdatePrompts]. [Unknown] covers the first frame of a composition. */
enum class UpdateSession { Unknown, SignedIn, SignedOut }

/**
 * Hosts the app's [content] (the nav host) under the update prompts, from `ShowTrackApp`.
 *
 * Nothing happens until [session] is [UpdateSession.SignedIn]: the check is not asked for from the
 * sign-in screen, and neither prompt ever covers it. [UpdateSession.SignedOut] (the sign-in screen
 * is showing, after Profile's Sign out, this screen's, or an expired session) ends the session's
 * update state, so the next account to sign in starts clean until its own check answers.
 * [UpdateSession.Unknown] does neither: a configuration change passes through it for one frame and
 * must not reset anything.
 *
 * `Available` puts [UpdateBanner] above the content. `Required` draws [UpdateRequiredScreen] OVER
 * the content rather than in place of it: the nav host carries `AuthGate`, which has to keep
 * listening so an expired session still lands on the sign-in screen. The covered content is
 * cleared from the semantics tree so TalkBack cannot reach under the blocking screen.
 *
 * [onSignedOut] is the host's navigation to sign-in, run once [UpdateUi.signedOut] flips. It is
 * the same "logout, then navigate" shape as Profile's Sign out, since `logout()` emits no auth
 * event. Every resume is reported ([UpdateActions.resumed]); the ViewModel ignores one with no
 * pending install. `LongParameterList` is suppressed for the same reason as [UpdateBanner].
 */
@Suppress("LongParameterList")
@Composable
fun UpdatePrompts(
    ui: UpdateUi,
    session: UpdateSession,
    actions: UpdateActions,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    when (session) {
        UpdateSession.SignedIn -> LaunchedEffect(Unit) { actions.check() }
        UpdateSession.SignedOut -> LaunchedEffect(Unit) { actions.sessionEnded() }
        UpdateSession.Unknown -> Unit
    }
    LifecycleResumeEffect(actions) {
        actions.resumed()
        onPauseOrDispose {}
    }
    val currentOnSignedOut by rememberUpdatedState(onSignedOut)
    LaunchedEffect(ui.signedOut) {
        if (ui.signedOut) currentOnSignedOut()
    }
    val signedIn = session == UpdateSession.SignedIn
    val available = (ui.state as? UpdateState.Available)?.takeIf { signedIn }
    val required = (ui.state as? UpdateState.Required)?.takeIf { signedIn }
    if (signedIn && ui.explainPermission) {
        InstallPermissionDialog(onOpenSettings = actions::confirmPermission, onDismiss = actions::dismissPermission)
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (available != null) {
            UpdateBanner(
                release = available.release,
                progress = ui.progress,
                failed = ui.failed,
                onInstall = actions::install,
                onLater = actions::later,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content(if (required != null) Modifier.clearAndSetSemantics {} else Modifier)
            if (required != null) {
                UpdateRequiredScreen(
                    release = required.release,
                    progress = ui.progress,
                    failed = ui.failed,
                    signOutFailed = ui.signOutFailed,
                    onInstall = actions::install,
                    onSignOut = actions::signOut,
                )
            }
        }
    }
}
