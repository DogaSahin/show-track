package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.GenreCount
import com.anarky.showtrack.core.model.ImportSummary
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.ImportSummaryDto
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryStatsDto
import java.math.BigDecimal
import java.time.Instant

/**
 * `BigDecimal(String)`, never a trip through `Double`. The API sends the score as a JSON STRING
 * specifically so this conversion can be exact, and `java.math.BigDecimal(score.toDouble())`
 * compiles, looks harmless, and reintroduces the IEEE 754 drift `NUMERIC(3,1)` exists to prevent
 * (backend decision 4-N): `"8.1"` becomes `8.0999999999999996447286321199499070644378662109375`.
 *
 * Two details that are easy to get backwards, both established by mutation testing this mapper.
 *
 * Kotlin's own `Double.toBigDecimal()` is not the same hazard: it is specified as
 * `BigDecimal(this.toString())`, and `Double.toString` emits the shortest decimal that
 * round-trips, so it is safe **for the shortest-form decimals this API sends**. It is value-safe,
 * not scale-safe — `"8.10"` would come back as `8.1` and `"7"` as `7.0`, and since
 * `BigDecimal.equals` compares scale and [LibraryEntry] is a data class, a backend that ever
 * emitted a trailing zero would make even that conversion observably wrong. `BigDecimal(String)`
 * has no such caveat, which is why it is what this uses.
 *
 * And `"8.5"` — the value the recorded wire fixture carries — is 17/2, exactly representable, so
 * it survives every unsafe conversion there is. Only `.0` and `.5` of the ten tenths a
 * `NUMERIC(3,1)` score can end in are; a test or an example that reaches for either is
 * demonstrating nothing, which is why the tests use `8.1`.
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
 * `by_status` keys that don't map to a known [UserMediaStatus] are DROPPED, not thrown on —
 * `mapNotNull` here matches `SearchMapper.toDomain`'s treatment of an unknown `MediaSource`: the
 * client must not crash when the server reports a status it has never heard of. The conversion
 * itself, `UserMediaStatus.valueOf(status.uppercase())`, is the exact expression [LibraryEntryDto.toDomain]
 * above uses for a single entry's status — reused rather than re-invented, just guarded with
 * `runCatching` here because THIS caller must survive an unknown key instead of throwing on it.
 *
 * `average_score` is `BigDecimal(String)`, never a trip through `Double` — see
 * [LibraryEntryDto.toDomain]'s own KDoc for why that conversion specifically (not
 * `Double.toBigDecimal()`) is the one that is scale-safe as well as value-safe. Its precision is
 * final: the server already applies `ROUND(avg, 1)` (backend's `get_stats` KDoc), so this parses
 * the string as-is rather than re-rounding or re-scaling it.
 */
fun LibraryStatsDto.toDomain(): LibraryStats =
    LibraryStats(
        total = total,
        byStatus =
            byStatus
                .mapNotNull { (status, count) ->
                    runCatching { UserMediaStatus.valueOf(status.uppercase()) }.getOrNull()?.let { it to count }
                }.toMap(),
        averageScore = averageScore?.let(::BigDecimal),
        ratedCount = ratedCount,
        episodesWatched = episodesWatched,
        // `map`, preserving order: the server has already ranked and capped this list, and
        // anything that re-sorted or re-collected it here would throw that ranking away silently.
        topGenres = topGenres.map { GenreCount(genre = it.genre, count = it.count) },
        addedThisMonth = addedThisMonth,
        favorites = favorites,
    )

/** A plain field-for-field copy — `ImportSummaryDto`'s fields already share [ImportSummary]'s names and types. */
fun ImportSummaryDto.toDomain(): ImportSummary =
    ImportSummary(imported = imported, skipped = skipped, failed = failed, truncated = truncated)

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
