package com.anarky.showtrack.core.model

import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class FeedEntryTest {
    private val actor = GroupActor(id = "user-1", username = "alex")
    private val media =
        MediaSummary(
            source = MediaSource.ANILIST,
            externalId = "ext-1",
            type = MediaType.ANIME,
            title = "Cowboy Bebop",
            year = 1998,
            genres = listOf("Action"),
            coverImageUrl = null,
        )

    @Test
    fun `constructing with media but no mediaId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeedEntry(
                id = "entry-1",
                actor = actor,
                kind = ActivityKind.ADDED,
                media = media,
                mediaId = null,
                payload = emptyMap(),
                createdAt = Instant.EPOCH,
            )
        }
    }

    @Test
    fun `constructing with mediaId but no media is rejected`() {
        // The exact E-H violation the check exists to catch: an imported row (media == null)
        // that some fake or preview fixture wrongly hands a mediaId, which would make the row
        // look tappable when it must not be.
        assertThrows(IllegalArgumentException::class.java) {
            FeedEntry(
                id = "entry-1",
                actor = actor,
                kind = ActivityKind.IMPORTED,
                media = null,
                mediaId = "media-1",
                payload = emptyMap(),
                createdAt = Instant.EPOCH,
            )
        }
    }

    @Test
    fun `both null, or both non-null, construct without error`() {
        FeedEntry(
            id = "entry-1",
            actor = actor,
            kind = ActivityKind.IMPORTED,
            media = null,
            mediaId = null,
            payload = emptyMap(),
            createdAt = Instant.EPOCH,
        )
        FeedEntry(
            id = "entry-2",
            actor = actor,
            kind = ActivityKind.ADDED,
            media = media,
            mediaId = "media-1",
            payload = emptyMap(),
            createdAt = Instant.EPOCH,
        )
    }
}
