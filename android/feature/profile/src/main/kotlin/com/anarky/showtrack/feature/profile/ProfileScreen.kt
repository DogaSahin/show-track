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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val pushState by viewModel.pushState.collectAsStateWithLifecycle()

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
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
        PushSection(
            state = pushState,
            onEnable = viewModel::enablePush,
            onDisable = viewModel::disablePush,
        )
    }
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
