package com.anarky.showtrack.feature.profile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.anarky.showtrack.feature.profile.alerts.EpisodeAlertNotifier

/** What the Episode alerts row shows (10-profile's three states). */
internal enum class AlertsRowState { Off, NeedsPermission, On }

/**
 * The switch is never on unless notifications are allowed. Alerts stored as on while the
 * permission was taken away, or a request the user just denied, both read as "allow notifications".
 */
internal fun alertsRowState(
    enabled: Boolean,
    notificationsAllowed: Boolean,
    denied: Boolean,
): AlertsRowState =
    when {
        enabled && notificationsAllowed -> AlertsRowState.On
        enabled || denied -> AlertsRowState.NeedsPermission
        else -> AlertsRowState.Off
    }

/** The row's state, plus what a tap does: ask Android's permission, open its settings, or switch. */
internal class AlertsRowController(
    val state: AlertsRowState,
    val onClick: () -> Unit,
)

@Composable
internal fun rememberAlertsRow(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
): AlertsRowController {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(EpisodeAlertNotifier.canNotify(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // Set when we send the user to Android's settings to allow notifications: coming back with
    // them allowed finishes what the tap started.
    var turnOnWhenAllowed by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        allowed = EpisodeAlertNotifier.canNotify(context)
        if (allowed) {
            denied = false
            if (turnOnWhenAllowed) {
                turnOnWhenAllowed = false
                onSetEnabled(true)
            }
        }
        onPauseOrDispose { }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = EpisodeAlertNotifier.canNotify(context)
            if (granted) onSetEnabled(true) else denied = true
        }

    val state = alertsRowState(enabled, allowed, denied)
    val openSettings = {
        turnOnWhenAllowed = true
        // Standard since API 26, but a tap must never crash on a phone that lacks the screen.
        try {
            context.startActivity(notificationSettings(context))
        } catch (missing: ActivityNotFoundException) {
            turnOnWhenAllowed = false
            Log.w("ShowTrackAlerts", "no notification settings screen: ${missing.javaClass.simpleName}")
        }
    }
    return AlertsRowController(state) {
        when {
            state == AlertsRowState.On -> onSetEnabled(false)
            state == AlertsRowState.NeedsPermission -> openSettings()
            allowed -> onSetEnabled(true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Before Android 13 there is no dialog to ask: notifications are blocked in settings.
            else -> openSettings()
        }
    }
}

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

@Composable
internal fun AlertsRow(
    state: AlertsRowState,
    onClick: () -> Unit,
    iconTile: Dp,
) {
    val on = state == AlertsRowState.On
    val subtitle =
        when (state) {
            AlertsRowState.On -> R.string.profile_alerts_on
            AlertsRowState.Off -> R.string.profile_alerts_off
            AlertsRowState.NeedsPermission -> R.string.profile_alerts_needs_permission
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                // One switch for TalkBack: the title and subtitle are its label, the Switch below
                // only draws.
                .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconTile),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_alerts),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.profile_alerts_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
