package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupActor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day grouping, tested as a pure function rather than through the rendered screen: the thing
 * that can actually be wrong here is where a boundary falls, and a Compose assertion on a header
 * string is a slow, indirect way to ask that question.
 */
class FeedTimelineTest {
    /**
     * The boundary is LOCAL, and this is the case that proves it. Both entries are on the same UTC
     * date; in Tokyo (UTC+9) the 23:00 one has already become the next day. Grouping in UTC would
     * put them under one heading, which is wrong for most of the world for part of every day.
     */
    @Test
    fun `entries are split by the reader's local day, not the UTC one`() {
        val entries =
            listOf(
                entry(id = "b", at = "2026-09-05T23:00:00Z"),
                entry(id = "a", at = "2026-09-05T09:00:00Z"),
            )

        val rows = entries.toTimeline(ZoneId.of("Asia/Tokyo"))

        assertEquals(
            listOf(
                FeedRow.Day(LocalDate.of(2026, 9, 6)),
                FeedRow.Entry(entries[0]),
                FeedRow.Day(LocalDate.of(2026, 9, 5)),
                FeedRow.Entry(entries[1]),
            ),
            rows,
        )
    }

    /**
     * A run of entries on one day gets ONE heading. The obvious wrong implementation — emitting a
     * heading per entry — renders identically for a one-entry-per-day fixture, so the fixture has
     * to have a run in it.
     */
    @Test
    fun `consecutive entries on the same day share a single heading`() {
        val entries =
            listOf(
                entry(id = "c", at = "2026-09-05T18:00:00Z"),
                entry(id = "b", at = "2026-09-05T12:00:00Z"),
                entry(id = "a", at = "2026-09-04T12:00:00Z"),
            )

        val rows = entries.toTimeline(ZoneId.of("UTC"))

        assertEquals(2, rows.count { it is FeedRow.Day })
        assertEquals(3, rows.count { it is FeedRow.Entry })
        assertEquals(FeedRow.Day(LocalDate.of(2026, 9, 5)), rows.first())
    }

    @Test
    fun `an empty feed produces no headings`() {
        assertEquals(emptyList<FeedRow>(), emptyList<FeedEntry>().toTimeline(ZoneId.of("UTC")))
    }

    private fun entry(
        id: String,
        at: String,
    ) = FeedEntry(
        id = id,
        actor = GroupActor(id = "user-1", username = "alex"),
        kind = ActivityKind.ADDED,
        media = null,
        mediaId = null,
        payload = emptyMap(),
        createdAt = Instant.parse(at),
    )
}
