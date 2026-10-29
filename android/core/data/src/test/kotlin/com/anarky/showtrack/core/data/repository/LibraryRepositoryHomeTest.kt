package com.anarky.showtrack.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.session.UserData
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.database.ShowTrackDatabase
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.MediaDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

/**
 * Home's half of [LibraryRepositoryImpl]: the per-section first pages, the Title-order cache they
 * write, and the stateless "Show all" page. Same real in-memory Room and recording fake API as
 * [LibraryRepositoryImplTest], split out only to keep each class a readable size.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryHomeTest {
    private lateinit var database: ShowTrackDatabase
    private lateinit var dao: LibraryDao
    private lateinit var api: FakeShowTrackApi
    private lateinit var repository: LibraryRepository

    private val cachedEntry =
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
            section = "WATCHING",
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShowTrackDatabase::class.java).build()
        dao = database.libraryDao()
        api = FakeShowTrackApi(emptyMap())
        repository = LibraryRepositoryImpl(api, dao, CountingAlerts())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `refreshHome in Title order asks for each section and caches every returned row`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))
            HOME_SECTIONS.forEach { section ->
                api.sectionPages[section.wireValue()] =
                    LibraryPageDto(
                        items = listOf(sectionDto(id = "in-${section.name}", section = section.wireValue())),
                        nextCursor = "more-${section.name}",
                    )
            }

            val pages = repository.refreshHome(LibrarySort.TITLE, perSection = 12)

            assertEquals(HOME_SECTIONS.map { it.wireValue() }.toSet(), api.requestedSections.toSet())
            assertEquals(HOME_SECTIONS.size, api.requestedSections.size)
            assertEquals(List(HOME_SECTIONS.size) { 12 }, api.requestedLimits)
            assertEquals(List(HOME_SECTIONS.size) { "title" }, api.requestedSorts)
            assertEquals(List(HOME_SECTIONS.size) { null }, api.requestedCursors)
            assertEquals(HOME_SECTIONS.toSet(), pages.keys)
            assertEquals("more-PAUSED", pages.getValue(LibrarySection.PAUSED).nextCursor)
            assertEquals(listOf("in-PAUSED"), pages.getValue(LibrarySection.PAUSED).items.map { it.id })
            // The old rows go: the cache is replaced wholesale, never merged.
            assertEquals(
                HOME_SECTIONS.map { "in-${it.name}" }.toSet(),
                dao
                    .observeAll()
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `observeHomeCache groups the cached rows by section in Title order`() =
        runTest {
            api.sectionPages["watching"] =
                LibraryPageDto(
                    items =
                        listOf(
                            sectionDto(id = "w2", title = "Vinland Saga", section = "watching"),
                            sectionDto(id = "w1", title = "Frieren", section = "watching"),
                        ),
                    nextCursor = null,
                )
            api.sectionPages["caught_up"] =
                LibraryPageDto(items = listOf(sectionDto(id = "c1", section = "caught_up")), nextCursor = null)

            repository.refreshHome(LibrarySort.TITLE, perSection = 12)

            val cache = repository.observeHomeCache().first()
            assertEquals(listOf("w1", "w2"), cache.getValue(LibrarySection.WATCHING).map { it.id })
            assertEquals(listOf("c1"), cache.getValue(LibrarySection.CAUGHT_UP).map { it.id })
            assertEquals(setOf(LibrarySection.WATCHING, LibrarySection.CAUGHT_UP), cache.keys)
        }

    /** The cache holds the default (Title) view only: another sort's first rows are the wrong rows. */
    @Test
    fun `refreshHome in another sort writes nothing to the cache`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))

            repository.refreshHome(LibrarySort.SCORE, perSection = 12)

            assertEquals(List(HOME_SECTIONS.size) { "score" }, api.requestedSorts)
            assertEquals(listOf(cachedEntry), dao.observeAll().first())
        }

    @Test
    fun `a failing section request throws and writes nothing`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))
            api.failingSections += "planned"

            val failure = runCatching { repository.refreshHome(LibrarySort.TITLE, perSection = 12) }

            assertTrue(failure.exceptionOrNull() is IOException)
            assertEquals(listOf(cachedEntry), dao.observeAll().first())
        }

    @Test
    fun `a refreshHome still in flight at sign-out does not put the old rows back`() =
        runTest {
            api.sectionPages["watching"] =
                LibraryPageDto(items = listOf(sectionDto(id = "w1", section = "watching")), nextCursor = null)
            val gate = CompletableDeferred<Unit>()
            api.libraryGate = gate
            val refresh = launch { repository.refreshHome(LibrarySort.TITLE, perSection = 12) }
            runCurrent()

            val clear = launch { (repository as UserData).clearUserData() }
            runCurrent()
            gate.complete(Unit)
            refresh.join()
            clear.join()

            assertEquals(emptyList<String>(), dao.observeAll().first().map { it.id })
        }

    @Test
    fun `sectionPage asks for one section from the given cursor and touches nothing else`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))
            api.sectionPages["completed"] =
                LibraryPageDto(items = listOf(sectionDto(id = "done", section = "completed")), nextCursor = "next")

            val page =
                repository.sectionPage(
                    LibrarySection.COMPLETED,
                    LibrarySort.NEXT_EPISODE_DATE,
                    cursor = "after",
                    limit = 30,
                )

            assertEquals(listOf("done"), page.items.map { it.id })
            assertEquals(LibrarySection.COMPLETED, page.items.single().section)
            assertEquals("next", page.nextCursor)
            assertEquals(listOf<String?>("completed"), api.requestedSections)
            assertEquals(listOf<String?>("after"), api.requestedCursors)
            assertEquals(listOf(30), api.requestedLimits)
            assertEquals(listOf<String?>("next_episode_date"), api.requestedSorts)
            assertEquals(listOf<String?>(null), api.requestedStatuses)
            assertEquals(listOf(cachedEntry), dao.observeAll().first())
        }

    private fun sectionDto(
        id: String,
        title: String = "Title $id",
        section: String,
    ) = LibraryEntryDto(
        id = id,
        status = "watching",
        score = null,
        progress = 12,
        favorite = false,
        updatedAt = "2026-08-26T13:41:10.558339Z",
        media =
            MediaDto(
                id = "media-$id",
                source = "anilist",
                externalId = id,
                type = "anime",
                title = title,
                year = 1998,
                genres = listOf("action"),
                coverImageUrl = "https://example.com/$id.jpg",
                status = "airing",
                nextEpisodeSeason = 1,
                nextEpisodeNumber = 2,
                nextEpisodeDate = "2026-08-30T14:16:00Z",
                daysUntilNextEpisode = 4,
            ),
        section = section,
    )

    private companion object {
        val HOME_SECTIONS = LibrarySection.entries - LibrarySection.UNKNOWN
    }
}
