package com.anarky.showtrack.core.data.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AlertSettingsStoreTest {
    // Through the app context, as production builds it: DataStore over a bare temp file cannot
    // rename its write file on Windows.
    private val store = DataStoreAlertSettingsStore(ApplicationProvider.getApplicationContext<Context>())
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    @Test
    fun `an alert fires once, and is forgotten only a day after its episode aired`() =
        runBlocking {
            store.forgetAccount()
            val airsAt = now.plus(Duration.ofHours(6))

            assertTrue(store.markFired("alert-a", airsAt, now))
            assertFalse(store.markFired("alert-a", airsAt, now))
            // Hours later, past airing but within a day: still remembered.
            assertFalse(store.markFired("alert-a", airsAt, now.plus(Duration.ofHours(20))))

            // Another alert recorded two days on prunes it.
            val later = now.plus(Duration.ofDays(2))
            assertTrue(store.markFired("alert-b", later.plus(Duration.ofHours(6)), later))
            assertTrue(store.markFired("alert-a", airsAt, later))
        }

    @Test
    fun `forgetting the account starts a new key and clears what fired`() =
        runBlocking {
            val before = store.alertKey()
            assertEquals(before, store.alertKey())
            store.markFired("alert-a", now, now)

            store.forgetAccount()

            assertNotEquals(before, store.alertKey())
            assertTrue(store.markFired("alert-a", now, now))
        }

    @Test
    fun `alerts are off until turned on`() =
        runBlocking {
            store.setEnabled(false)
            assertFalse(store.enabled.first())

            store.setEnabled(true)

            assertTrue(store.enabled.first())
        }
}
