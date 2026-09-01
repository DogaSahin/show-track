package com.anarky.showtrack.core.database

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room has no built-in mapping for `java.time.Instant`, so [LibraryEntryEntity.updatedAt] needs
 * one explicit converter pair to compile at all. Registered on [ShowTrackDatabase] rather than
 * per-entity, so a second entity added later reuses it instead of redeclaring it.
 *
 * The column this produces is INTEGER epoch millis, which is what makes
 * `LibraryDao.observeAll()`'s `ORDER BY updated_at DESC` a numeric comparison rather than a
 * lexicographic one over a formatted string.
 */
class Converters {
    @TypeConverter
    fun toInstant(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)

    @TypeConverter
    fun fromInstant(instant: Instant): Long = instant.toEpochMilli()
}
