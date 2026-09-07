package com.anarky.showtrack.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * A single-table cache database, not a general-purpose local store — see [LibraryEntryEntity]
 * for why. `exportSchema` is false rather than pointed at a committed schema directory: every
 * row this database holds is reconstructible from the next successful network fetch, so there is
 * no migration history worth keeping. A future version bump is handled destructively (see
 * `di.DatabaseModule`), which is what a schema-history file would otherwise exist to inform.
 */
@Database(
    entities = [LibraryEntryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ShowTrackDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
