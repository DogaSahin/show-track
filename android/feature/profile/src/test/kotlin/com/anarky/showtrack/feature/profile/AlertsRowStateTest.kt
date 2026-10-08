package com.anarky.showtrack.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertsRowStateTest {
    @Test
    fun `the switch is on only when alerts are on and notifications are allowed`() {
        assertEquals(AlertsRowState.On, alertsRowState(enabled = true, notificationsAllowed = true, denied = false))
        assertEquals(AlertsRowState.Off, alertsRowState(enabled = false, notificationsAllowed = true, denied = false))
        assertEquals(AlertsRowState.Off, alertsRowState(enabled = false, notificationsAllowed = false, denied = false))
    }

    @Test
    fun `a denied request, or permission taken away later, asks for notifications`() {
        assertEquals(
            AlertsRowState.NeedsPermission,
            alertsRowState(enabled = false, notificationsAllowed = false, denied = true),
        )
        assertEquals(
            AlertsRowState.NeedsPermission,
            alertsRowState(enabled = true, notificationsAllowed = false, denied = false),
        )
    }
}
