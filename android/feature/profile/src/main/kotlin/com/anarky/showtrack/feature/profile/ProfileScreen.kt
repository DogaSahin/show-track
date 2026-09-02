package com.anarky.showtrack.feature.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus

/**
 * [onSignedOut] fires exactly once, right after `AuthRepository.logout()` completes — keyed on
 * [ProfileViewModel.signedOut] the same way `AuthScreen`'s `onAuthenticated` is keyed on
 * `AuthUiState`. It is not the reactive `AuthGate` in `:app`: `logout()` clears the session
 * without emitting `AuthEvent.LoggedOut` (that event is reserved for a failed token refresh — see
 * `ProfileViewModel.signOut`'s KDoc), so this explicit callback is the only thing that sends a
 * signed-out user back to the auth screen. `ProfileNavigation.kt` turns it into
 * `onNavigate(AuthRoute)`.
 */
@Composable
fun ProfileScreen(
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val pushState by viewModel.pushState.collectAsStateWithLifecycle()
    val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
    val signOutError by viewModel.signOutError.collectAsStateWithLifecycle()
    val statsState by viewModel.statsState.collectAsStateWithLifecycle()
    LaunchedEffect(signedOut) {
        if (signedOut) onSignedOut()
    }

    // ON RESUME, not just in the ViewModel's `init`, and this is the difference between the
    // NoDistributor prompt working and being a dead end. The prompt tells the user to go and
    // install ntfy; doing so takes them out of the app and back. The ViewModel is scoped to the
    // NavBackStackEntry and survives that round trip, so its `init` does not run again — the
    // screen would still say "push needs one more app" after they had installed the app it asked
    // for. That is decision A-A's own failure mode wearing the prompt written to prevent it.
    //
    // LifecycleResumeEffect rather than LaunchedEffect(Unit): the state is a function of what is
    // installed on the DEVICE, and PackageManager offers no flow to observe. Resume is exactly
    // when the answer can have changed.
    //
    // Two calls, not one (review finding, round 1): `refresh()` is push's synchronous read;
    // `refreshStats()` is the stats network fetch. They used to be one function — folding the
    // stats fetch into `refresh()` made `init` (below) and this effect both fire it on cold start
    // with no ordering guarantee between the two, and made every push toggle silently re-fetch
    // stats too. See `ProfileViewModel`'s own KDoc for the full account.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        viewModel.refreshStats()
        onPauseOrDispose { }
    }

    ProfileScreen(
        pushState = pushState,
        statsState = statsState,
        signOutError = signOutError,
        onEnablePush = viewModel::enablePush,
        onDisablePush = viewModel::disablePush,
        onStatsRetry = viewModel::refreshStats,
        onSignOut = viewModel::signOut,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph, a
 * ViewModel, or Hilt — `LibraryScreen`/`FavoritesScreen`'s pattern. `ProfileScreenTest` drives
 * this directly to pin the two behaviours no ViewModel test can see: which STRING an unrated
 * library renders (never "0.0" — see [StatsContent]'s own KDoc) and that an absent status renders
 * as absent, not zero.
 *
 * The sign-out confirmation dialog's own `showSignOutConfirmation` flag lives here, not in the
 * caller above: it is pure Compose UI state with no ViewModel counterpart, the same way
 * `LibraryScreen`'s stateless overload owns whatever purely-visual state it needs.
 *
 * Eight parameters trips detekt's `LongParameterList` (threshold 6); suppressed rather than
 * bundling the callbacks into an `Actions` holder class, matching `LibraryScreen`/`DiscoverScreen`'s
 * own identical suppression for the identical reason — a holder that exists for this one call site
 * only is indirection without fewer moving parts.
 */
@Suppress("LongParameterList")
@Composable
internal fun ProfileScreen(
    pushState: PushState,
    statsState: LibraryStatsUiState,
    signOutError: Boolean,
    onEnablePush: (String) -> Unit,
    onDisablePush: () -> Unit,
    onStatsRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
        PushSection(
            state = pushState,
            onEnable = onEnablePush,
            onDisable = onDisablePush,
        )
        StatsSection(
            state = statsState,
            onRetry = onStatsRetry,
        )
        SignOutSection(
            error = signOutError,
            onSignOutClick = { showSignOutConfirmation = true },
        )
    }

    // A confirmation step because signing out discards local session state (tokens, the push
    // registration) that the tap cannot undo — the same reasoning `LibraryList`'s tap-to-retry
    // footer does NOT need, since a retry there costs nothing if it was a mistake.
    if (showSignOutConfirmation) {
        SignOutConfirmationDialog(
            onDismiss = { showSignOutConfirmation = false },
            onConfirm = {
                showSignOutConfirmation = false
                onSignOut()
            },
        )
    }
}

/**
 * The sign-out button and its own failure channel — split out of [ProfileScreen] purely to keep
 * that function under detekt's `LongMethod` threshold now that it also hosts [StatsSection].
 */
@Composable
private fun SignOutSection(
    error: Boolean,
    onSignOutClick: () -> Unit,
) {
    TextButton(onClick = onSignOutClick) {
        Text(text = stringResource(R.string.profile_sign_out))
    }
    // signOut() only ever sets this on a caught failure — see its KDoc for why signedOut is NOT
    // flipped in that case, which is what makes leaving the user here, able to retry, the correct
    // response rather than a dead end.
    if (error) {
        Text(
            text = stringResource(R.string.profile_sign_out_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SignOutConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.profile_sign_out_confirm_title)) },
        text = { Text(text = stringResource(R.string.profile_sign_out_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.profile_sign_out_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profile_sign_out_cancel))
            }
        },
    )
}

/**
 * The one screen in the app whose job is to explain an absence.
 *
 * [PushState.NoDistributor] is not an error and not an empty state: nothing is broken, the user
 * simply does not have the second app UnifiedPush requires. Rendering it as a failure — or worse,
 * rendering nothing — turns "push needs one more app" into "push is broken", which is the
 * conclusion a silent version of this screen invites (decision A-A).
 */
@Composable
private fun PushSection(
    state: PushState,
    onEnable: (String) -> Unit,
    onDisable: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is PushState.NoDistributor -> {
                    Text(
                        text = stringResource(R.string.push_no_distributor_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.push_no_distributor_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is PushState.Available -> {
                    Text(
                        text = stringResource(R.string.push_available_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.distributors.forEach { distributor ->
                        // The package name, unresolved to a label on purpose: resolving it needs
                        // a PackageManager round trip per row for a list that is almost always
                        // one entry long, and "org.unifiedpush.distributor.ntfy" is already
                        // recognisable to someone who just installed it.
                        Text(text = distributor, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onEnable(distributor) }) {
                            Text(text = stringResource(R.string.push_enable))
                        }
                    }
                }

                is PushState.Registered -> {
                    Text(
                        text = stringResource(R.string.push_registered_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = state.distributor, style = MaterialTheme.typography.bodyMedium)
                    NotificationPermissionPrompt()
                    TextButton(onClick = onDisable) {
                        Text(text = stringResource(R.string.push_disable))
                    }
                }
            }
        }
    }
}

/**
 * The second thing that makes a delivered notification invisible, and it has nothing to do with
 * UnifiedPush: on API 33+ a post without `POST_NOTIFICATIONS` is silently DROPPED. Registration
 * would look perfect and nothing would arrive.
 *
 * Asked here rather than at app launch because a permission prompt makes sense next to the
 * feature that needs it, and because it is only reachable once push is actually on.
 */
@Composable
private fun NotificationPermissionPrompt() {
    val context = LocalContext.current
    // Below API 33 the permission does not exist and is granted by definition. `remember` with no
    // key: the value can only change through the launcher below, which sets it directly.
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            granted = result
        }

    if (granted) return
    Text(text = stringResource(R.string.push_permission_body), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
        Text(text = stringResource(R.string.push_permission_grant))
    }
}

/**
 * The library-stats block (task 9b.5). [LibraryStatsUiState.Loading]/[LibraryStatsUiState.Error]
 * reuse `:core:designsystem`'s [LoadingState]/[ErrorState] rather than hand-rolled equivalents
 * (decision C-T) — the same components `FavoritesScreen`/`LibraryScreen` already use for the
 * identical two states.
 */
@Composable
private fun StatsSection(
    state: LibraryStatsUiState,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.profile_stats_title), style = MaterialTheme.typography.titleMedium)
            when (state) {
                is LibraryStatsUiState.Loading -> LoadingState()
                is LibraryStatsUiState.Error ->
                    ErrorState(message = stringResource(R.string.profile_stats_error), onRetry = onRetry)
                is LibraryStatsUiState.Success -> {
                    // `isStale` (decision C-B, `FavoritesScreen`'s identical shape): the banner sits
                    // ABOVE the numbers rather than replacing them — a resume's failed background
                    // refetch leaves stats that are still worth showing, just not guaranteed current.
                    if (state.isStale) {
                        StaleDataBanner(onRetry = onRetry)
                    }
                    StatsContent(stats = state.stats)
                }
            }
        }
    }
}

/**
 * A status absent from [LibraryStats.byStatus] renders as absent, not as zero (the server sends
 * what exists — see [LibraryStats]'s own KDoc), which is why this iterates [UserMediaStatus.entries]
 * for a STABLE row order and skips whatever [LibraryStats.byStatus] does not carry, rather than
 * iterating the map itself.
 *
 * [LibraryStats.averageScore]'s precision is the server's job, already done (`ROUND(avg, 1)`) — this
 * renders [java.math.BigDecimal.toPlainString] as-is, with no further rounding or formatting, and
 * null renders as "no ratings yet" rather than a `0.0` that would falsely claim every title was
 * rated zero.
 */
@Composable
private fun StatsContent(stats: LibraryStats) {
    Text(
        text = pluralStringResource(R.plurals.profile_stats_total, stats.total, stats.total),
        style = MaterialTheme.typography.bodyMedium,
    )
    UserMediaStatus.entries.forEach { status ->
        val count = stats.byStatus[status] ?: return@forEach
        Text(
            text = stringResource(R.string.profile_stats_status_row, status.label(), count),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val average = stats.averageScore
    Text(
        text =
            if (average != null) {
                pluralStringResource(
                    R.plurals.profile_stats_average,
                    stats.ratedCount,
                    average.toPlainString(),
                    stats.ratedCount,
                )
            } else {
                stringResource(R.string.profile_stats_no_ratings)
            },
        style = MaterialTheme.typography.bodyMedium,
    )
}
