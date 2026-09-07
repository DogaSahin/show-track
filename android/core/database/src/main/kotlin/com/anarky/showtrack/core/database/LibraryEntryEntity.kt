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
 * Room is consulted on cold start only, so nothing here is ever consulted to decide truth: the
 * network response always wins, and this table is fully replaced by [LibraryDao.replaceAll] on
 * every successful fetch.
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
    // Not optional: LibraryDao.observeAll() orders by this column, so a numeric (epoch-millis)
    // representation is what makes that ORDER BY well-defined — a string column would depend on
    // getting the timestamp format exactly fixed-width and zero-padded to sort the same way.
    // [Converters] is what lets the Kotlin-facing type stay `Instant` while the stored column is
    // still that INTEGER.
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    val title: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "days_until_next_episode")
    val daysUntilNextEpisode: Int?,
)
