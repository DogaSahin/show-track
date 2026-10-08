package com.anarky.showtrack.feature.profile.alerts

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlertRulesTest {
    @Test
    fun `an episode 30 hours away gets an alert 24 h and one 6 h before`() {
        val plan = AlertRules.plan(listOf(watching(airsIn = Duration.ofHours(30))), NOW)

        assertEquals(listOf(AlertLead.DAY, AlertLead.SOON), plan.map { it.lead })
        assertEquals(listOf(Duration.ofHours(6), Duration.ofHours(24)), plan.map { it.delay })
        assertEquals("alert-media-1-2-7-24h", plan.first().name)
    }

    @Test
    fun `an episode 12 hours away gets only the 6 h alert`() {
        val plan = AlertRules.plan(listOf(watching(airsIn = Duration.ofHours(12))), NOW)

        assertEquals(listOf(AlertLead.SOON to Duration.ofHours(6)), plan.map { it.lead to it.delay })
    }

    @Test
    fun `an episode first seen 3 hours before airing gets exactly one alert, now`() {
        val plan = AlertRules.plan(listOf(watching(airsIn = Duration.ofHours(3))), NOW)

        assertEquals(listOf(AlertLead.SOON to Duration.ZERO), plan.map { it.lead to it.delay })
        // The same name as the ordinary 6 h alert, so one that already showed is not repeated.
        assertEquals(AlertRules.name("media-1", 2, 7, AlertLead.SOON), plan.single().name)
    }

    @Test
    fun `an episode that already aired, or has no known date, gets nothing`() {
        val aired = watching(airsIn = Duration.ofMinutes(-1))
        val unknown = watching(airsIn = null)

        assertEquals(emptyList<PlannedAlert>(), AlertRules.plan(listOf(aired, unknown), NOW))
    }

    @Test
    fun `only Watching titles get alerts`() {
        val paused = watching(airsIn = Duration.ofHours(30)).copy(status = UserMediaStatus.PAUSED)

        assertEquals(emptyList<PlannedAlert>(), AlertRules.plan(listOf(paused), NOW))
    }

    @Test
    fun `a title with no season is named with season 0`() {
        val anime = watching(airsIn = Duration.ofHours(10), season = null)

        assertEquals("alert-media-1-0-7-6h", AlertRules.plan(listOf(anime), NOW).single().name)
    }

    private fun watching(
        airsIn: Duration?,
        season: Int? = 2,
    ) = LibraryEntry(
        id = "entry-1",
        status = UserMediaStatus.WATCHING,
        score = null,
        progress = 6,
        favorite = false,
        updatedAt = NOW,
        media =
            Media(
                id = "media-1",
                source = MediaSource.TMDB,
                externalId = "95396",
                type = MediaType.TV,
                title = "Severance",
                year = 2022,
                genres = emptyList(),
                coverImageUrl = null,
                status = MediaStatus.AIRING,
                nextEpisodeSeason = season,
                nextEpisodeNumber = 7,
                nextEpisodeDate = airsIn?.let { NOW.plus(it) },
                daysUntilNextEpisode = null,
            ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-07T12:00:00Z")
    }
}
