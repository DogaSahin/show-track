package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.MediaDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Every expectation here is a LITERAL, never `input.toDomain()` compared against another call to
 * the same mapper. That distinction is the point of the file: an assertion phrased as
 * `assertEquals(entity.toDomain(), observed)` asserts the mapper against itself and survives any
 * field of the mapping being deleted. Verified by mutation — hard-coding
 * `coverImageUrl = null` in [LibraryEntryEntity.toDomain] killed no test until this file existed,
 * and the cover URL is precisely what a library list row draws.
 *
 * Both mappers are long runs of field-to-field transcription, so the bug they actually attract is
 * a dropped or transposed field, not a logic error.
 */
class MapperTest {
    @Test
    fun `a media dto maps every field into the domain`() {
        val media = mediaDto().toDomain()

        assertEquals("media-1", media.id)
        // Wire enums arrive lower-case; the domain enums are not.
        assertEquals(MediaSource.ANILIST, media.source)
        assertEquals(MediaType.ANIME, media.type)
        assertEquals(MediaStatus.AIRING, media.status)
        assertEquals("21", media.externalId)
        assertEquals("One Piece", media.title)
        assertEquals(1999, media.year)
        assertEquals(listOf("action", "adventure"), media.genres)
        assertEquals("https://example.com/1.jpg", media.coverImageUrl)
        assertEquals(1, media.nextEpisodeSeason)
        assertEquals(1176, media.nextEpisodeNumber)
        assertEquals(Instant.parse("2026-08-30T14:16:00Z"), media.nextEpisodeDate)
        assertEquals(4, media.daysUntilNextEpisode)
    }

    @Test
    fun `a finished title carries null airing fields rather than defaults`() {
        val media =
            mediaDto()
                .copy(
                    status = "finished",
                    nextEpisodeSeason = null,
                    nextEpisodeNumber = null,
                    nextEpisodeDate = null,
                    daysUntilNextEpisode = null,
                ).toDomain()

        assertEquals(MediaStatus.FINISHED, media.status)
        assertEquals(null, media.nextEpisodeDate)
        assertEquals(null, media.daysUntilNextEpisode)
    }

    @Test
    fun `a cached row maps back to the domain fields a list row draws`() {
        val entry = cachedEntity().toDomain()

        assertEquals("cached", entry.id)
        assertEquals(UserMediaStatus.WATCHING, entry.status)
        assertEquals(BigDecimal("7.0"), entry.score)
        assertEquals(3, entry.progress)
        assertFalse(entry.favorite)
        assertEquals(Instant.ofEpochMilli(1_000L), entry.updatedAt)
        assertEquals("media-cached", entry.media.id)
        assertEquals("Cached Title", entry.media.title)
        assertEquals("https://example.com/cached.jpg", entry.media.coverImageUrl)
        assertEquals(5, entry.media.daysUntilNextEpisode)
    }

    @Test
    fun `the domain maps onto the cache row a list needs and nothing more`() {
        val entity = cachedEntity().toDomain().toEntity()

        assertEquals("cached", entity.id)
        // `status.name`, not the wire spelling — which is why the entity mapper reads it back
        // without an `uppercase()`.
        assertEquals("WATCHING", entity.status)
        assertEquals("7.0", entity.score)
        assertEquals(3, entity.progress)
        assertFalse(entity.favorite)
        assertEquals(Instant.ofEpochMilli(1_000L), entity.updatedAt)
        assertEquals("media-cached", entity.mediaId)
        assertEquals("Cached Title", entity.title)
        assertEquals("https://example.com/cached.jpg", entity.coverUrl)
        assertEquals(5, entity.daysUntilNextEpisode)
    }

    private fun mediaDto() =
        MediaDto(
            id = "media-1",
            source = "anilist",
            externalId = "21",
            type = "anime",
            title = "One Piece",
            year = 1999,
            genres = listOf("action", "adventure"),
            coverImageUrl = "https://example.com/1.jpg",
            status = "airing",
            nextEpisodeSeason = 1,
            nextEpisodeNumber = 1176,
            nextEpisodeDate = "2026-08-30T14:16:00Z",
            daysUntilNextEpisode = 4,
        )

    private fun cachedEntity() =
        LibraryEntryEntity(
            id = "cached",
            status = "WATCHING",
            score = "7.0",
            progress = 3,
            favorite = false,
            updatedAt = Instant.ofEpochMilli(1_000L),
            mediaId = "media-cached",
            title = "Cached Title",
            coverUrl = "https://example.com/cached.jpg",
            daysUntilNextEpisode = 5,
        )
}
