package com.anarky.showtrack.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_entries ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LibraryEntryEntity>>

    /**
     * The only intended write path — [clear] and [insertAll] exist to be composed here, not to
     * be called independently.
     *
     * `@Transaction` is load-bearing, not decoration: without it, a process death between
     * [clear] and [insertAll] leaves the table empty, and the next cold start renders nothing
     * instead of the stale-but-useful content this cache exists to provide. With it, SQLite
     * guarantees the two statements commit or roll back together.
     */
    @Transaction
    suspend fun replaceAll(entries: List<LibraryEntryEntity>) {
        clear()
        insertAll(entries)
    }

    @Query("DELETE FROM library_entries")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LibraryEntryEntity>)
}
