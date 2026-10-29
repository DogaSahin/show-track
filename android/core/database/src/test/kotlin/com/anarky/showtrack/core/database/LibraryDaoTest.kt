package com.anarky.showtrack.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Room DAO tests need a real SQLite, which a plain JVM unit test does not have — but
 * [RobolectricTestRunner] provides one: Robolectric ships a native SQLite implementation for the
 * host JVM, so this runs the actual DAO/database code against actual SQLite, not a fake. That
 * makes it a genuine JVM unit test rather than an androidTest — it runs in the ordinary
 * `testDebugUnitTest` gate, on every CI run, unlike `:core:network`'s Keystore instrumentation
 * test, which has no off-device equivalent at all.
 *
 * The SDK Robolectric runs against is pinned in `src/test/resources/robolectric.properties`
 * (`sdk=35`), not per-class here: `sdk=35` rather than this module's `compileSdk` (36) because
 * Robolectric selects its Android platform shadow independently of `compileSdk`, and 35 is the
 * newest level `robolectric:4.15.1` ships a shadow for — `sdk=36` fails fast with
 * `IllegalArgumentException: API level 36 is not available` (confirmed by trying it). Compiling
 * against 36 while testing against 35 is Robolectric's normal arrangement, not a workaround. A
 * module-wide properties file, rather than `@Config(sdk = [35])` on this class, is what makes
 * that pin apply to every Robolectric test class added to this module later, not just this one.
 */
@RunWith(RobolectricTestRunner::class)
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
     * Home draws each section in Title order straight from this flow, so the ORDER BY is what the
     * offline screen shows. A fixture inserted in title order could not tell a correct ORDER BY
     * from none, hence the shuffled insert; the lower-case title pins the case-insensitive collation.
     */
    @Test
    fun observeAll_orders_by_title_ignoring_case() =
        runTest {
            dao.replaceAll(
                listOf(
                    libraryEntry(id = "c", title = "Cowboy Bebop"),
                    libraryEntry(id = "a", title = "akira"),
                    libraryEntry(id = "b", title = "Berserk"),
                ),
            )

            dao.observeAll().test {
                assertEquals(listOf("a", "b", "c"), awaitItem().map { it.id })
            }
        }

    @Test
    fun section_next_episode_season_and_updatedAt_round_trip() =
        runTest {
            val caughtUp =
                libraryEntry(id = "1").copy(
                    section = "CAUGHT_UP",
                    nextEpisodeSeason = 2,
                    updatedAt = Instant.ofEpochMilli(3_000L),
                )

            dao.replaceAll(listOf(caughtUp, libraryEntry(id = "2")))

            dao.observeAll().test {
                val rows = awaitItem().associateBy { it.id }
                assertEquals(caughtUp, rows.getValue("1"))
                assertEquals("WATCHING", rows.getValue("2").section)
                assertEquals(null, rows.getValue("2").nextEpisodeSeason)
            }
        }

    /**
     * The behaviour `@Transaction` exists to make atomic in production. This test proves the
     * *replacement* semantics — a second `replaceAll` leaves only its own rows, never a union of
     * both calls. It does NOT prove the transaction itself: nothing here crashes between `clear`
     * and `insertAll`, so a version of this DAO missing `@Transaction` would pass it too. There is
     * no way to inject a crash mid-transaction from a test, on-device or off; the annotation's
     * crash-safety is argued from SQLite's documented transaction guarantee, not exercised here.
     * [LibraryDaoTransactionGuardTest] is what actually pins the annotation's presence.
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

    private fun libraryEntry(
        id: String,
        title: String = "Title $id",
    ) = LibraryEntryEntity(
        id = id,
        status = "WATCHING",
        score = "8.5",
        progress = 3,
        favorite = false,
        updatedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        mediaId = "media-$id",
        title = title,
        coverUrl = "https://example.com/$id.jpg",
        daysUntilNextEpisode = 2,
        section = "WATCHING",
    )
}
