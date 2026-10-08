package com.anarky.showtrack.feature.profile

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.LargeTitleScaffold
import com.anarky.showtrack.core.designsystem.component.UserAvatar
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.CurrentUser
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AvatarSize = 58.dp
private val RowIconTile = 32.dp

/**
 * [onSignedOut] fires exactly once, right after `AuthRepository.logout()` completes — keyed on
 * [ProfileViewModel.signedOut] the same way `AuthScreen`'s `onAuthenticated` is keyed on
 * `AuthUiState`. It is not the reactive `AuthGate` in `:app`: `logout()` clears the session
 * without emitting `AuthEvent.LoggedOut` (that event is reserved for a failed token refresh — see
 * `ProfileViewModel.signOut`'s KDoc), so this explicit callback is the only thing that sends a
 * signed-out user back to the auth screen. `ProfileNavigation.kt` turns it into
 * `onNavigate(AuthRoute)`.
 *
 * [activeGroup] arrives as a value from `:app`, the way Feed receives it (E-C): the Groups row's
 * subtitle names your groups without this module reading a singleton for them.
 *
 * The account and the stats both reload on every resume: a trip to Groups, Import or Search and
 * back can change either, and this ViewModel survives that round trip, so `init` would not run
 * again.
 */
@Suppress("LongParameterList")
@Composable
fun ProfileScreen(
    activeGroup: StateFlow<ActiveGroupState>,
    onSignedOut: () -> Unit,
    onGroupsClick: () -> Unit,
    onImportClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
    val signOutError by viewModel.signOutError.collectAsStateWithLifecycle()
    val statsState by viewModel.statsState.collectAsStateWithLifecycle()
    val groups by activeGroup.collectAsStateWithLifecycle()
    val alertsEnabled by viewModel.alertsEnabled.collectAsStateWithLifecycle()
    val alerts = rememberAlertsRow(enabled = alertsEnabled, onSetEnabled = viewModel::setAlertsEnabled)
    LaunchedEffect(signedOut) {
        if (signedOut) onSignedOut()
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshUser()
        viewModel.refreshStats()
        onPauseOrDispose { }
    }

    ProfileScreen(
        user = user,
        groupNames = (groups as? ActiveGroupState.Success)?.groups?.map { it.name },
        statsState = statsState,
        signOutError = signOutError,
        alerts = alerts.state,
        onAlertsClick = alerts.onClick,
        onStatsRetry = viewModel::refreshStats,
        onSignOut = viewModel::signOut,
        onGroupsClick = onGroupsClick,
        onImportClick = onImportClick,
        onSearchClick = onSearchClick,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel.
 *
 * Stats first, because they are the interesting part; the settings you visit rarely sit below as
 * grouped rows. [groupNames] is null while the groups are unknown (loading or failed), which leaves
 * the Groups row without a subtitle rather than wrongly saying you have none.
 *
 * [alerts] is worked out by the stateful overload (it needs the permission APIs); a tap on the
 * row is [onAlertsClick], whatever it then has to do.
 */
@Suppress("LongParameterList")
@Composable
internal fun ProfileScreen(
    user: CurrentUser?,
    groupNames: List<String>?,
    statsState: LibraryStatsUiState,
    signOutError: Boolean,
    alerts: AlertsRowState,
    onAlertsClick: () -> Unit,
    onStatsRetry: () -> Unit,
    onSignOut: () -> Unit,
    onGroupsClick: () -> Unit,
    onImportClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    LargeTitleScaffold(title = stringResource(R.string.profile_title), modifier = modifier.fillMaxSize()) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 20.dp),
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            user?.let { YouHeader(user = it) }
            StatsBlock(state = statsState, onRetry = onStatsRetry, onSearch = onSearchClick)
            AppSection(
                groupNames = groupNames,
                signOutError = signOutError,
                alerts = alerts,
                onAlertsClick = onAlertsClick,
                onGroupsClick = onGroupsClick,
                onImportClick = onImportClick,
                onSignOutClick = { showSignOutConfirmation = true },
            )
        }
    }

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

/** The rarely visited part: doors to Groups and Import, then Sign out on its own card. */
@Suppress("LongParameterList")
@Composable
private fun AppSection(
    groupNames: List<String>?,
    signOutError: Boolean,
    alerts: AlertsRowState,
    onAlertsClick: () -> Unit,
    onGroupsClick: () -> Unit,
    onImportClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(space = 10.dp)) {
        SectionLabel(text = stringResource(R.string.profile_section_app))
        SettingsCard {
            SettingsRow(
                iconRes = R.drawable.ic_groups,
                title = stringResource(R.string.profile_groups_title),
                subtitle = groupNames?.groupsSubtitle(),
                onClick = onGroupsClick,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                iconRes = R.drawable.ic_import,
                title = stringResource(R.string.profile_import_title),
                subtitle = stringResource(R.string.profile_import_subtitle),
                onClick = onImportClick,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AlertsRow(state = alerts, onClick = onAlertsClick, iconTile = RowIconTile)
        }
        SettingsCard {
            Text(
                text = stringResource(R.string.profile_sign_out),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSignOutClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        if (signOutError) {
            Text(
                text = stringResource(R.string.profile_sign_out_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun List<String>.groupsSubtitle(): String =
    if (isEmpty()) stringResource(R.string.profile_groups_subtitle_none) else joinToString(separator = ", ")

/** Avatar, username, email, and the month the account was made. */
@Composable
private fun YouHeader(user: CurrentUser) {
    val locale = LocalConfiguration.current.locales[0]
    val since =
        user.createdAt
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 14.dp),
    ) {
        UserAvatar(userId = user.id, name = user.username, size = AvatarSize)
        Column(verticalArrangement = Arrangement.spacedBy(space = 2.dp)) {
            Text(
                text = user.username,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.profile_member_since, since),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** An icon tile, a title and subtitle, and a chevron: a door to another screen. */
@Composable
private fun SettingsRow(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(RowIconTile),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(
                    text = stringResource(R.string.profile_sign_out_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profile_sign_out_cancel))
            }
        },
    )
}
