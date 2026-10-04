package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class EpisodeRulesTest {
    @Test
    fun `catching up offers the aired, unwatched episodes before it in the same season`() {
        // S2E6 marked with E1-E3 watched and E4-E5 not: offer E4-E5, never season 1 or E7.
        val catchUp = EpisodeRules.catchUpFor(LIST, watched = ids("s2e1", "s2e2", "s2e3", "s2e6"), episode = ep(2, 6))

        assertEquals(CatchUp(season = 2, fromNumber = 4, toNumber = 5, episodeIds = ids("s2e4", "s2e5")), catchUp)
    }

    @Test
    fun `nothing to catch up when everything before is watched`() {
        assertNull(EpisodeRules.catchUpFor(LIST, watched = ids("s2e1", "s2e2"), episode = ep(2, 3)))
    }

    @Test
    fun `the season holding the next unwatched episode opens by default`() {
        val watchedAllOfSeasonOne = ids("s1e1", "s1e2")

        assertEquals(setOf(2), EpisodeRules.defaultExpanded(LIST, watchedAllOfSeasonOne, tracking = true))
        assertEquals(setOf(1), EpisodeRules.defaultExpanded(LIST, emptySet(), tracking = true))
    }

    @Test
    fun `not tracking opens the season still airing`() {
        assertEquals(setOf(2), EpisodeRules.defaultExpanded(LIST, emptySet(), tracking = false))
    }

    @Test
    fun `a season action covers aired episodes only`() {
        assertEquals((1..6).map { "s2e$it" }.toSet(), EpisodeRules.airedIds(LIST.seasons[1]))
    }

    private companion object {
        fun ep(
            season: Int,
            number: Int,
            aired: Boolean = true,
        ) = Episode(id = "s${season}e$number", number = number, title = null, airDate = null, aired = aired)

        fun ids(vararg ids: String) = ids.toSet()

        // Season 1: two aired. Season 2: six aired, E7 not yet.
        val LIST =
            EpisodeList(
                syncedAt = Instant.parse("2026-10-01T08:00:00Z"),
                totalEpisodes = 9,
                seasons =
                    listOf(
                        Season(number = 1, episodes = listOf(ep(1, 1), ep(1, 2))),
                        Season(number = 2, episodes = (1..6).map { ep(2, it) } + ep(2, 7, aired = false)),
                    ),
            )
    }
}
