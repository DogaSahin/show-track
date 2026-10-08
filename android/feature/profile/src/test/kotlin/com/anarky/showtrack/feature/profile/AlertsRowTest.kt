package com.anarky.showtrack.feature.profile

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What a tap on the alerts row does. Android's own permission dialog is device-only. */
@RunWith(RobolectricTestRunner::class)
class AlertsRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val writes = mutableListOf<Boolean>()

    private fun show(enabled: Boolean) {
        composeRule.setContent {
            val row = rememberAlertsRow(enabled = enabled, onSetEnabled = { writes += it })
            AlertsRow(state = row.state, onClick = row.onClick, iconTile = 32.dp)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `with notifications allowed a tap turns alerts on`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show(enabled = false)

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(listOf(true), writes)
    }

    @Test
    fun `a tap on an on switch turns alerts off`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show(enabled = true)

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(listOf(false), writes)
    }

    @Test
    fun `without permission the tap opens notification settings, and coming back allowed turns alerts on`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show(enabled = true)
        composeRule.onNode(isToggleable()).assertIsOff().performClick()

        val opened = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(emptyList<Boolean>(), writes)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertEquals(listOf(true), writes)
    }
}
