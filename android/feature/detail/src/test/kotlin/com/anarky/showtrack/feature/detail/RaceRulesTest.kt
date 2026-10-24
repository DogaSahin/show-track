package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Season
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RaceRulesTest {
    @Test
    fun `a big group shows the six closest to you, you included`() {
        val members = (1..10).map { member("u$it", progress = it * 10) }

        val shown = RaceRules.trackMembers(members, meId = "u1").map { it.member.id }

        assertEquals(listOf("u1", "u2", "u3", "u4", "u5", "u6"), shown)
    }

    @Test
    fun `pins alternate above and below, and members on one episode stack`() {
        val pins = RaceRules.pins(listOf(member("a", 5), member("b", 5), member("c", 9)), meId = "c")

        assertEquals(listOf(true, false, true), pins.map { it.above })
        assertEquals(listOf(0, 1, 0), pins.map { it.stackIndex })
        assertEquals(listOf(false, false, true), pins.map { it.isMe })
    }

    @Test
    fun `the line ends at the last episode, or at the furthest member while that is unknown`() {
        assertEquals(19, RaceRules.trackEnd(total = 19, members = listOf(member("a", 4))))
        assertEquals(7, RaceRules.trackEnd(total = null, members = listOf(member("a", 4), member("b", 7))))
    }

    @Test
    fun `standing against you is ahead, behind, level or finished`() {
        assertEquals(Standing.Ahead(2), RaceRules.standing(member("a", 12), mine = 10))
        assertEquals(Standing.Behind(3), RaceRules.standing(member("a", 7), mine = 10))
        assertEquals(Standing.Level, RaceRules.standing(member("a", 10), mine = 10))
        assertEquals(Standing.Finished, RaceRules.standing(member("a", 3, UserMediaStatus.COMPLETED), mine = 10))
    }

    @Test
    fun `a progress count is placed as season and episode, with ticks between seasons`() {
        assertEquals(2 to 1, RaceRules.episodeAt(10, LIST))
        assertEquals(1 to 9, RaceRules.episodeAt(9, LIST))
        assertNull(RaceRules.episodeAt(99, LIST))
        assertNull(RaceRules.episodeAt(3, null))
        assertEquals(listOf(9), RaceRules.seasonTicks(LIST))
    }

    @Test
    fun `in a big group you sit among the closest on both sides`() {
        val members = (1..10).map { member("u$it", progress = it * 10) }

        val shown = RaceRules.trackMembers(members, meId = "u6").map { it.member.id }.toSet()

        assertEquals(setOf("u6", "u5", "u7", "u4", "u8", "u3").size, shown.size)
        assertEquals(true, "u6" in shown && "u1" !in shown && "u10" !in shown)
    }

    @Test
    fun `you not in the group shows the first six`() {
        val members = (1..10).map { member("u$it", progress = it) }

        assertEquals(6, RaceRules.trackMembers(members, meId = "stranger").size)
    }

    @Test
    fun `a single-season title is placed by episode alone`() {
        val anime = EpisodeList(syncedAt = Instant.EPOCH, totalEpisodes = 12, seasons = listOf(season(1, 12)))

        assertEquals(null to 8, RaceRules.episodeAt(8, anime))
    }

    @Test
    fun `your row takes the progress saved on this screen, and goes once you removed the title`() {
        val members = listOf(member("me", 14), member("bob", 20))

        val updated = RaceRules.withMyEntry(members, "me", myProgress = 15, myStatus = UserMediaStatus.WATCHING)
        val removed = RaceRules.withMyEntry(members, "me", myProgress = null, myStatus = null)

        assertEquals(15, updated.first { it.member.id == "me" }.progress)
        assertEquals(listOf("bob"), removed.map { it.member.id })
    }

    private companion object {
        fun member(
            id: String,
            progress: Int,
            status: UserMediaStatus = UserMediaStatus.WATCHING,
        ) = MemberProgress(member = GroupActor(id = id, username = id), status = status, progress = progress)

        fun season(
            number: Int,
            count: Int,
        ) = Season(
            number = number,
            episodes =
                (1..count).map {
                    Episode(
                        id = "s${number}e$it",
                        number = it,
                        title = null,
                        airDate = null,
                        aired = true,
                    )
                },
        )

        val LIST =
            EpisodeList(syncedAt = Instant.EPOCH, totalEpisodes = 19, seasons = listOf(season(1, 9), season(2, 10)))
    }
}
