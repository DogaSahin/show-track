package com.anarky.showtrack.feature.profile.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun text(
        airsIn: Duration,
        season: Int? = 2,
    ) = AlertText.body(resources, season, 7, now.plus(airsIn), now)

    @Test
    fun `the wording follows how soon the episode actually airs`() {
        assertEquals("S2 E7 airs tomorrow", text(Duration.ofHours(24)))
        assertEquals("S2 E7 airs in 6 hours", text(Duration.ofHours(6)))
        // Shown late (first seen 3 hours before, or held back by Doze): still the truth.
        assertEquals("S2 E7 airs in 3 hours", text(Duration.ofMinutes(170)))
        assertEquals("S2 E7 airs in 1 hour", text(Duration.ofMinutes(60)))
        assertEquals("S2 E7 airs soon", text(Duration.ofMinutes(20)))
    }

    @Test
    fun `a title without seasons names the episode alone`() {
        assertEquals("E7 airs in 6 hours", text(Duration.ofHours(6), season = null))
    }
}
