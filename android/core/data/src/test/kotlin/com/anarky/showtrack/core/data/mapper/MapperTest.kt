package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryStatsDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.PersistedMediaDto
import com.anarky.showtrack.core.network.dto.RecommendationDto
import com.anarky.showtrack.core.network.dto.RecommendationReasonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * The mapper on the PRIMARY read path, and the one this file originally missed. `progress`
     * and `favorite` in particular had no literal expectation anywhere: hard-coding them to
     * `0` and `false` passed the whole suite, which would have shipped every library row reading
     * `0/24 episodes` with an un-favourited heart, on a green gate.
     */
    @Test
    fun `a library entry dto maps every field into the domain`() {
        val entry = libraryEntryDto().toDomain()

        assertEquals("entry-1", entry.id)
        // Wire status is lower-case; the domain enum is not.
        assertEquals(UserMediaStatus.WATCHING, entry.status)
        assertEquals(BigDecimal("8.1"), entry.score)
        assertEquals(12, entry.progress)
        assertTrue(entry.favorite)
        assertEquals(Instant.parse("2026-08-26T13:41:10.558339Z"), entry.updatedAt)
        // The nested media is mapped, not dropped or defaulted.
        assertEquals("media-1", entry.media.id)
        assertEquals("One Piece", entry.media.title)
    }

    @Test
    fun `an unscored entry maps to a null score rather than zero`() {
        assertNull(libraryEntryDto().copy(score = null).toDomain().score)
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

    /**
     * `PersistedMediaDto` carries no status and no next-episode block at all, unlike `MediaDto` —
     * this pins the mapper's own documented DEFAULT for those fields (NOT_YET_AIRED / all-null),
     * literally, rather than merely asserting the fields the DTO does carry. A mapper that started
     * defaulting `status` to `AIRING` instead would pass every other test in this file and would
     * have a recommendation row rendering an airing badge it has no data to back.
     */
    @Test
    fun `a persisted media dto maps into a media with unpopulated airing fields`() {
        val media = persistedMediaDto().toDomain()

        assertEquals("media-1", media.id)
        assertEquals(MediaSource.ANILIST, media.source)
        assertEquals(MediaType.ANIME, media.type)
        assertEquals("21", media.externalId)
        assertEquals("One Piece", media.title)
        assertEquals(1999, media.year)
        assertEquals(listOf("action", "adventure"), media.genres)
        assertEquals("https://example.com/1.jpg", media.coverImageUrl)
        assertEquals(MediaStatus.NOT_YET_AIRED, media.status)
        assertNull(media.nextEpisodeSeason)
        assertNull(media.nextEpisodeNumber)
        assertNull(media.nextEpisodeDate)
        assertNull(media.daysUntilNextEpisode)
    }

    /** The reason maps through untouched — no default-substitution risk here, unlike the media half. */
    @Test
    fun `a recommendation dto maps its media and its one seed reason`() {
        val recommendation =
            RecommendationDto(
                media = persistedMediaDto(),
                reason =
                    RecommendationReasonDto(
                        seedMediaId = "seed-1",
                        seedTitle = "Made in Abyss",
                        matchedGenres = listOf("fantasy", "adventure"),
                    ),
            ).toDomain()

        assertEquals("media-1", recommendation.media.id)
        assertEquals("seed-1", recommendation.reason.seedMediaId)
        assertEquals("Made in Abyss", recommendation.reason.seedTitle)
        assertEquals(listOf("fantasy", "adventure"), recommendation.reason.matchedGenres)
    }

    /** Task 9b.5. Literal expectations, not `dto.toDomain()` against itself — this file's own rule. */
    @Test
    fun `a library stats dto maps every field into the domain`() {
        val stats =
            LibraryStatsDto(
                total = 7,
                byStatus = mapOf("watching" to 5, "completed" to 2),
                averageScore = "8.4",
                ratedCount = 3,
            ).toDomain()

        assertEquals(7, stats.total)
        assertEquals(mapOf(UserMediaStatus.WATCHING to 5, UserMediaStatus.COMPLETED to 2), stats.byStatus)
        // BigDecimal(String), never a trip through Double — see LibraryEntryDto.toDomain's own
        // KDoc for why that specific conversion is scale-safe as well as value-safe. "8.4" is not
        // exactly representable as an IEEE 754 double, so a `BigDecimal(score.toDouble())` mutant
        // fails this on the value alone, without needing 8.1's dyadic-rational argument.
        assertEquals(BigDecimal("8.4"), stats.averageScore)
        assertEquals(3, stats.ratedCount)
    }

    @Test
    fun `an unrated library maps to a null average rather than zero`() {
        val stats = LibraryStatsDto(total = 3, byStatus = emptyMap(), averageScore = null, ratedCount = 0).toDomain()

        assertNull(stats.averageScore)
    }

    /**
     * `by_status` keys the client does not recognise are DROPPED, matching `SearchMapper`'s
     * treatment of an unknown `MediaSource` — the client must not crash when the server reports a
     * status it has never heard of. `total` is left untouched (it is the server's own sum, not
     * recomputed from the surviving keys), so a dropped key does not silently corrupt the total.
     */
    @Test
    fun `an unrecognised status key is dropped from the map rather than thrown on`() {
        val stats =
            LibraryStatsDto(
                total = 6,
                byStatus = mapOf("watching" to 4, "on_hold_legacy" to 2),
                averageScore = null,
                ratedCount = 0,
            ).toDomain()

        assertEquals(mapOf(UserMediaStatus.WATCHING to 4), stats.byStatus)
        assertEquals(6, stats.total)
    }

    private fun persistedMediaDto() =
        PersistedMediaDto(
            id = "media-1",
            source = "anilist",
            externalId = "21",
            type = "anime",
            title = "One Piece",
            year = 1999,
            genres = listOf("action", "adventure"),
            coverImageUrl = "https://example.com/1.jpg",
        )

    private fun libraryEntryDto() =
        LibraryEntryDto(
            id = "entry-1",
            status = "watching",
            score = "8.1",
            progress = 12,
            favorite = true,
            updatedAt = "2026-08-26T13:41:10.558339Z",
            media = mediaDto(),
        )

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
