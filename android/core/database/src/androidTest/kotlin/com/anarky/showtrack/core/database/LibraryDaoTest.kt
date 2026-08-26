package com.anarky.showtrack.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Room DAO tests need a real SQLite, which the JVM unit-test classpath does not have — so this
 * is an androidTest, not a `src/test` unit test.
 *
 * **Not in the gate.** `./gradlew :core:database:assembleDebugAndroidTest` compiles this so it
 * cannot rot unnoticed; running it needs `./gradlew :core:database:connectedDebugAndroidTest`
 * against a device or emulator, and there is neither in this environment. Whether it passes on a
 * real device has not been verified — see `TokenStoreInstrumentationTest` in `:core:network` for
 * the same situation and the same reasoning.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {
    private lateinit var database: ShowTrackDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In-memory, not "showtrack.db": each test gets an empty table and nothing it writes can
        // leak into (or be polluted by) a real app install on the same device.
        database =
            Room
                .inMemoryDatabaseBuilder(context, ShowTrackDatabase::class.java)
                .build()
        dao = database.libraryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun observeAll_emits_after_replaceAll() =
        runTest {
            val entry = libraryEntry(id = "1")

            dao.observeAll().test {
                assertEquals(emptyList<LibraryEntryEntity>(), awaitItem())

                dao.replaceAll(listOf(entry))

                assertEquals(listOf(entry), awaitItem())
            }
        }

    /**
     * The behaviour `@Transaction` exists to make atomic in production. This test proves the
     * *replacement* semantics — a second `replaceAll` leaves only its own rows, never a union of
     * both calls. It does NOT prove the transaction itself: nothing here crashes between `clear`
     * and `insertAll`, so a version of this DAO missing `@Transaction` would pass it too. There is
     * no off-device way to inject a crash mid-transaction, so the annotation's crash-safety is
     * argued from SQLite's documented transaction guarantee, not exercised by this test.
     */
    @Test
    fun replaceAll_twice_leaves_only_the_second_set() =
        runTest {
            dao.replaceAll(listOf(libraryEntry(id = "1"), libraryEntry(id = "2")))

            dao.replaceAll(listOf(libraryEntry(id = "3")))

            dao.observeAll().test {
                assertEquals(listOf(libraryEntry(id = "3")), awaitItem())
            }
        }

    private fun libraryEntry(id: String) =
        LibraryEntryEntity(
            id = id,
            status = "WATCHING",
            score = "8.5",
            progress = 3,
            favorite = false,
            updatedAt = Instant.ofEpochMilli(1_700_000_000_000L),
            mediaId = "media-$id",
            title = "Title $id",
            coverUrl = "https://example.com/$id.jpg",
            daysUntilNextEpisode = 2,
        )
}
