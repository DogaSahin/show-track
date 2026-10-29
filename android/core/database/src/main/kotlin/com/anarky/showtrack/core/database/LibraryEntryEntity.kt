package com.anarky.showtrack.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A render cache row: exactly what the library list screen draws, flattened, and nothing else.
 * It is not a mirror of the API response (`LibraryEntryDto` in `:core:network` keeps the nested
 * `MediaDto`) and not the domain aggregate (`:core:model.LibraryEntry` nests a full `Media`).
 * `:core:database` depends on neither module — mapping between this table and either of those
 * shapes is `:core:data`'s job, done once per read/write rather than baked into the schema.
 *
 * It holds what Home draws offline: the first rows of each display section, in Title order. Room
 * is consulted on cold start only, so nothing here is ever consulted to decide truth: the network
 * response always wins, and this table is fully replaced by [LibraryDao.replaceAll] on every
 * successful Home refresh in Title order.
 *
 * `score` stays a raw, nullable `String` for the same reason `LibraryEntryDto.score` does: the
 * backend sends it as a string specifically to preserve `NUMERIC(3,1)` precision, and routing it
 * through anything narrower (`Double`, or even parsing to `BigDecimal` before it is cached) would
 * reintroduce the IEEE-754 drift that representation exists to avoid.
 */
@Entity(tableName = "library_entries")
data class LibraryEntryEntity(
    @PrimaryKey
    val id: String,
    val status: String,
    val score: String?,
    val progress: Int,
    val favorite: Boolean,
    // Stored as INTEGER epoch millis: [Converters] lets the Kotlin-facing type stay `Instant`, and a
    // numeric column keeps any ORDER BY on it well-defined, unlike a formatted timestamp string.
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    val title: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "days_until_next_episode")
    val daysUntilNextEpisode: Int?,
    // The title's episode count, for "14 of 28 watched"; null until the server has its list.
    @ColumnInfo(name = "total_episodes")
    val totalEpisodes: Int? = null,
    // `LibrarySection.name` (UNKNOWN included); `:core:data` decodes it tolerantly, like [status].
    // The server derives it, so Home groups cached rows by it and never re-derives it from [status].
    val section: String,
    // For the caught-up "More soon" line; null when no next episode is scheduled.
    @ColumnInfo(name = "next_episode_season")
    val nextEpisodeSeason: Int? = null,
)
