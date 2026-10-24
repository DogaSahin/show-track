package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Progress merging and payload reading: the parts of the feed that can be wrong without looking wrong. */
class FeedEventsTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `adjacent progress updates by one person on one show on one day merge, newest first`() {
        val newest = progressed(id = "c", progress = 15, at = "2026-09-05T20:00:00Z")
        val entries =
            listOf(
                newest,
                progressed(id = "b", progress = 14, at = "2026-09-05T19:00:00Z"),
                progressed(id = "a", progress = 13, at = "2026-09-05T18:00:00Z"),
            )

        val items = entries.mergeProgress(utc)

        assertEquals(listOf(FeedItem(entry = newest, oldestProgress = 13)), items)
        assertEquals(FeedDetail.Progress(from = 13, to = 15), items.single().detail())
    }

    @Test
    fun `a different person, show or day, or an event in between, keeps updates apart`() {
        val entries =
            listOf(
                progressed(id = "f", progress = 9, at = "2026-09-06T08:00:00Z"),
                // Same show and person, but the previous local day.
                progressed(id = "e", progress = 8, at = "2026-09-05T22:00:00Z"),
                progressed(id = "d", progress = 7, at = "2026-09-05T21:00:00Z", actorId = "user-2"),
                progressed(id = "c", progress = 6, at = "2026-09-05T20:00:00Z", mediaId = "media-2"),
                entry(
                    id = "b",
                    kind = ActivityKind.RATED,
                    payload = mapOf("score" to "8.0"),
                    at = "2026-09-05T19:00:00Z",
                ),
                progressed(id = "a", progress = 5, at = "2026-09-05T18:00:00Z"),
            )

        assertEquals(entries.map { it.id }, entries.mergeProgress(utc).map { it.entry.id })
    }

    @Test
    fun `a page that continues the last run merges into it`() {
        val firstPage = listOf(progressed(id = "b", progress = 15, at = "2026-09-05T20:00:00Z"))
        val secondPage = listOf(progressed(id = "a", progress = 12, at = "2026-09-05T18:00:00Z"))

        val items = (firstPage + secondPage).mergeProgress(utc)

        assertEquals(listOf("b"), items.map { it.entry.id })
        assertEquals(12, items.single().oldestProgress)
    }

    @Test
    fun `a single update, or a run ending where it started, shows one episode number`() {
        val single = FeedItem(progressed(id = "a", progress = 4, at = "2026-09-05T18:00:00Z"))
        val flat =
            listOf(
                progressed(id = "b", progress = 4, at = "2026-09-05T19:00:00Z"),
                progressed(id = "a", progress = 4, at = "2026-09-05T18:00:00Z"),
            ).mergeProgress(utc).single()

        assertEquals(FeedDetail.Progress(from = null, to = 4), single.detail())
        assertEquals(FeedDetail.Progress(from = null, to = 4), flat.detail())
    }

    @Test
    fun `payload values are read when present and readable`() {
        assertEquals(
            FeedDetail.Score(value = "9.5", kind = ActivityKind.RATED),
            FeedItem(entry(kind = ActivityKind.RATED, payload = mapOf("score" to "9.5"))).detail(),
        )
        assertEquals(
            FeedDetail.DroppedAt(episode = 5),
            FeedItem(
                entry(kind = ActivityKind.DROPPED, payload = mapOf("progress" to "5", "status" to "dropped")),
            ).detail(),
        )
        assertEquals(
            FeedDetail.Status(UserMediaStatus.PLANNED),
            FeedItem(entry(kind = ActivityKind.ADDED, payload = mapOf("status" to "planned"))).detail(),
        )
        assertEquals(
            ScoreChange.Cleared,
            entry(kind = ActivityKind.RATED, payload = mapOf("score" to "null")).scoreChange(),
        )
    }

    @Test
    fun `missing or unreadable payload values give no chip rather than a broken one`() {
        val unreadable =
            listOf(
                entry(kind = ActivityKind.RATED, payload = mapOf("score" to "great")),
                entry(kind = ActivityKind.RATED, payload = mapOf("score" to "null")),
                entry(kind = ActivityKind.COMPLETED, payload = emptyMap()),
                entry(kind = ActivityKind.DROPPED, payload = mapOf("progress" to "0")),
                entry(kind = ActivityKind.ADDED, payload = mapOf("status" to "binged")),
                entry(kind = ActivityKind.PROGRESSED, payload = mapOf("progress" to "five")),
            )

        unreadable.forEach { assertNull(it.toString(), FeedItem(it).detail()) }
    }

    private fun progressed(
        id: String,
        progress: Int,
        at: String,
        actorId: String = "user-1",
        mediaId: String = "media-1",
    ) = entry(id = id, kind = ActivityKind.PROGRESSED, payload = mapOf("progress" to progress.toString()), at = at)
        .copy(actor = GroupActor(id = actorId, username = "alex"), mediaId = mediaId)

    private fun entry(
        id: String = "e",
        kind: ActivityKind,
        payload: Map<String, String>,
        at: String = "2026-09-05T18:00:00Z",
    ) = FeedEntry(
        id = id,
        actor = GroupActor(id = "user-1", username = "alex"),
        kind = kind,
        media = MEDIA,
        mediaId = "media-1",
        payload = payload,
        createdAt = Instant.parse(at),
    )

    private companion object {
        val MEDIA =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "1",
                type = MediaType.ANIME,
                title = "Frieren",
                year = 2023,
                genres = emptyList(),
                coverImageUrl = null,
            )
    }
}
