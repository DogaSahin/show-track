package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import java.math.BigDecimal
import java.time.Instant

/**
 * `BigDecimal(String)`, never a trip through `Double`. The API sends the score as a JSON STRING
 * specifically so this conversion can be exact, and `java.math.BigDecimal(score.toDouble())`
 * compiles, looks harmless, and reintroduces the IEEE 754 drift `NUMERIC(3,1)` exists to prevent
 * (backend decision 4-N): `"8.1"` becomes `8.0999999999999996447286321199499070644378662109375`.
 *
 * Two details that are easy to get backwards, both established by mutation testing this mapper.
 * Kotlin's own `Double.toBigDecimal()` is NOT the hazard — it is specified as
 * `BigDecimal(this.toString())`, and `Double.toString` already emits the shortest decimal that
 * round-trips, so it survives. And `"8.5"` — the value the recorded wire fixture carries — is
 * 17/2, exactly representable, so it survives every unsafe conversion there is. Only `.0` and
 * `.5` of the ten tenths a `NUMERIC(3,1)` score can end in are; a test or an example that reaches
 * for either is demonstrating nothing, which is why the tests use `8.1`.
 */
fun LibraryEntryDto.toDomain(): LibraryEntry =
    LibraryEntry(
        id = id,
        status = UserMediaStatus.valueOf(status.uppercase()),
        score = score?.let(::BigDecimal),
        progress = progress,
        favorite = favorite,
        updatedAt = Instant.parse(updatedAt),
        media = media.toDomain(),
    )

/**
 * The cache is a RENDER cache: it holds only what a list row draws, so the [Media] it
 * reconstructs is partial by design — `externalId`, `year` and `genres` are placeholders and
 * `source`/`type`/`status` are not the persisted values. Anything needing a full [Media] reads
 * through the network path, which is what keeps this a cache rather than a second source of
 * truth. Widening the table to make this mapper total is the change that would quietly turn
 * Room into the thing the app believes, so it is not a gap to be "fixed" in passing.
 *
 * `status` needs no `uppercase()` here, unlike the DTO mapper: [toEntity] writes `status.name`,
 * so the column already holds the enum's own spelling.
 */
fun LibraryEntryEntity.toDomain(): LibraryEntry =
    LibraryEntry(
        id = id,
        status = UserMediaStatus.valueOf(status),
        score = score?.let(::BigDecimal),
        progress = progress,
        favorite = favorite,
        // Already an `Instant`: :core:database's `Converters` owns the epoch-millis column type,
        // so the conversion the cache needs is not this mapper's business.
        updatedAt = updatedAt,
        media =
            Media(
                id = mediaId,
                source = MediaSource.ANILIST,
                externalId = "",
                type = MediaType.ANIME,
                title = title,
                year = null,
                genres = emptyList(),
                coverImageUrl = coverUrl,
                status = MediaStatus.AIRING,
                nextEpisodeSeason = null,
                nextEpisodeNumber = null,
                nextEpisodeDate = null,
                daysUntilNextEpisode = daysUntilNextEpisode,
            ),
    )

/**
 * `toPlainString()` rather than `toString()`: `BigDecimal.toString()` emits scientific notation
 * once the scale goes negative (`BigDecimal("8.5").setScale(-1)` prints `1E+1`), and a cached
 * `"1E+1"` is not the string any reader of that column expects.
 */
fun LibraryEntry.toEntity(): LibraryEntryEntity =
    LibraryEntryEntity(
        id = id,
        status = status.name,
        score = score?.toPlainString(),
        progress = progress,
        favorite = favorite,
        updatedAt = updatedAt,
        mediaId = media.id,
        title = media.title,
        coverUrl = media.coverImageUrl,
        daysUntilNextEpisode = media.daysUntilNextEpisode,
    )
