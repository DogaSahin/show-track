package com.anarky.showtrack.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.mapper.toEntity
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.database.ShowTrackDatabase
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * A REAL in-memory Room database, not a hand-written fake DAO — which is a test-design decision
 * worth stating. A fake cannot have transaction semantics, so `@Transaction` on
 * `LibraryDao.replaceAll` would be unexercisable from here and the cache path — the entire
 * reason `:core:database` exists — would ship untested end to end. Robolectric ships a native
 * SQLite for the host JVM, so this runs inside the ordinary `testDebugUnitTest` gate rather than
 * needing a device. The SDK pin lives in `src/test/resources/robolectric.properties`.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryImplTest {
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
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ShowTrackDatabase::class.java)
                .build()
        dao = database.libraryDao()
        api =
            FakeShowTrackApi(
                mapOf(
                    null to LibraryPageDto(items = listOf(dto(id = "1")), nextCursor = "c1"),
                    "c1" to LibraryPageDto(items = listOf(dto(id = "2")), nextCursor = null),
                ),
            )
        repository = LibraryRepositoryImpl(api, dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the cache emits before the network resolves`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))

            repository.observeLibrary().test {
                // No network call yet: the first emission is purely the cold-start cache.
                // Fields, not `cachedEntry.toDomain()` — comparing the emission to the mapper's
                // own output asserts the mapper against itself and holds for a broken mapper too.
                val cold = awaitItem().single()
                assertEquals("cached", cold.id)
                assertEquals("Cached Title", cold.media.title)
                assertEquals(BigDecimal("7.0"), cold.score)
                assertEquals(3, cold.progress)
                assertEquals(emptyList<String?>(), api.requestedCursors)

                repository.refresh()

                val fresh = awaitItem().single()
                assertEquals("1", fresh.id)
                assertEquals("Title 1", fresh.media.title)
                assertEquals(UserMediaStatus.WATCHING, fresh.status)
                // Full microsecond precision, and that is the assertion doing the work: the
                // network list takes over WHOLESALE, so what is emitted here came straight off
                // the wire rather than back out of SQLite. Asserting the truncated value would
                // pass whether the emission came from the cache or the network, which is exactly
                // the distinction this test exists to pin.
                assertEquals(Instant.parse("2026-08-26T13:41:10.558339Z"), fresh.updatedAt)

                // The other half of the asymmetry, and the reason a mapper round trip is NOT a
                // cache round trip: the row that was written for the next cold start is
                // truncated to millis, because `library_entries.updated_at` is INTEGER epoch
                // millis. Harmless (the field feeds ordering and display, never equality) but a
                // hand-written fake DAO would never have shown it.
                val cachedRow = dao.observeAll().first().single()
                assertEquals(Instant.parse("2026-08-26T13:41:10.558Z"), cachedRow.updatedAt)
            }
        }

    /**
     * The test the repository had no way to fail before `observeLibrary()` combined the paginator
     * in. The cache is deliberately first-page-only, so pages 2..n live nowhere but
     * `CursorPaginator.items` — with `observeLibrary()` returning the DAO flow alone, `loadMore()`
     * fetched and mapped a page that reached no consumer, and a screen calling it would have seen
     * nothing happen with nothing to diagnose.
     */
    @Test
    fun `loadMore appends the next page to what observeLibrary emits`() =
        runTest {
            repository.refresh()

            repository.observeLibrary().test {
                assertEquals(listOf("1"), awaitItem().map { it.id })

                repository.loadMore()

                assertEquals(listOf("1", "2"), awaitItem().map { it.id })
            }
        }

    /**
     * The cold-start property the `combine` must not break: with nothing paged yet, the cache is
     * what renders. `ifEmpty` rather than a `started`-style flag because the paginator's own list
     * already carries that information.
     */
    @Test
    fun `the cache still wins when nothing has been paged`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))

            assertEquals(listOf("cached"), repository.observeLibrary().first().map { it.id })
        }

    /**
     * `8.1`, and NOT the `8.5` the wire fixture happens to carry — the value is the whole test.
     * 8.5 is 17/2, a dyadic rational, so it is EXACTLY representable as an IEEE 754 double and
     * every lossy conversion of it round-trips correctly. Of the ten tenths a `NUMERIC(3,1)`
     * score can end in, only `.0` and `.5` are exactly representable, and both the brief and the
     * recorded wire fixture use `8.5`. On `8.1`, `BigDecimal(score.toDouble())` yields
     * `8.0999999999999996447286321199499070644378662109375` — the drift `NUMERIC(3,1)` and the
     * string-typed wire field exist to prevent (backend decision 4-N).
     *
     * Confirmed by mutation, including the direction that surprises: `BigDecimal(double)` is
     * killed here and survives on `8.5`, while Kotlin's own `Double.toBigDecimal()` survives
     * BOTH, because it is specified as `BigDecimal(this.toString())` and `Double.toString`
     * already emits the shortest round-tripping decimal. So the hazard is narrower than "any
     * trip through Double" — it is the raw `java.math.BigDecimal(double)` constructor.
     */
    @Test
    fun `a score round-trips as an exact decimal`() {
        val entry = dto(id = "1", score = "8.1").toDomain()

        assertEquals(BigDecimal("8.1"), entry.score)
        // And survives the cache, which stores it as a string for the same reason the API does.
        assertEquals(BigDecimal("8.1"), entry.toEntity().toDomain().score)
        assertEquals("8.1", entry.toEntity().score)
    }

    /**
     * The mutation `toPlainString()` -> `toString()` survives every score the backend actually
     * sends, because `NUMERIC(3,1)` never produces a negative scale. This is the case that kills
     * it: a `BigDecimal` whose scale went negative anywhere upstream caches as `"1E+1"`, and the
     * column is read by anything that treats it as a displayable number.
     */
    @Test
    fun `a score is cached in plain notation, never scientific`() {
        val scientific = BigDecimal("8.5").setScale(-1, RoundingMode.HALF_UP)
        assertEquals("1E+1", scientific.toString()) // the trap is real, not hypothetical

        val entry = dto(id = "1").toDomain().copy(score = scientific)

        assertEquals("10", entry.toEntity().score)
    }

    @Test
    fun `a failed refresh leaves the cache intact`() =
        runTest {
            dao.replaceAll(listOf(cachedEntry))
            api.failNext()

            runCatching { repository.refresh() }

            assertEquals(listOf(cachedEntry), dao.observeAll().first())
        }

    /**
     * The half the DAO assertion above cannot see. `CursorPaginator.restart` fetches before it
     * mutates, so a failed refresh must emit NOTHING — the user keeps the rows they were looking
     * at. Clearing first would drop a 40-row list to whatever 20 rows the cache happens to hold.
     *
     * The absence of an emission is asserted by the turbine block itself: an unexpected item is
     * an unconsumed event and fails at the end of the block.
     */
    @Test
    fun `a failed refresh leaves the on-screen list standing`() =
        runTest {
            repository.refresh()
            repository.loadMore()

            repository.observeLibrary().test {
                assertEquals(listOf("1", "2"), awaitItem().map { it.id })

                api.failNext()
                runCatching { repository.refresh() }
            }
        }

    /**
     * The emission sequence a pull-to-refresh actually produces, which is the property an earlier
     * version got wrong invisibly. With the paginator cleared BEFORE the network call, refreshing
     * a two-page list emitted the stale first-page cache for the whole round trip:
     *
     *     [1(wire), 2(wire)] -> [1(millis)] <- stale cache -> [1(wire)]
     *
     * — 40 rows collapsing to 20 stale ones and back, so a pull-to-refresh jumped twice and lost
     * its scroll position. Fetching before mutating makes it one transition. The millisecond
     * precision on the middle emission is what identified it as cache-sourced, and is why this
     * test asserts `updatedAt` rather than just the ids.
     */
    @Test
    fun `a refresh goes straight to the fresh page without a stale detour`() =
        runTest {
            repository.refresh()
            repository.loadMore()

            repository.observeLibrary().test {
                assertEquals(listOf("1", "2"), awaitItem().map { it.id })

                repository.refresh()

                val afterRefresh = awaitItem()
                assertEquals(listOf("1"), afterRefresh.map { it.id })
                // Wire precision: this is the FRESH page. The stale cache row would be the same
                // id truncated to millis, which is exactly the emission that must not appear.
                assertEquals(
                    Instant.parse("2026-08-26T13:41:10.558339Z"),
                    afterRefresh.single().updatedAt,
                )
            }
        }

    /**
     * `refresh()` must reset the paginator, not continue it. Without the reset the second call
     * would request `c1` and write page 2 into the cache as though it were page 1 — a cold start
     * would then open on the middle of the list.
     */
    @Test
    fun `refresh restarts from the first page rather than continuing`() =
        runTest {
            repository.refresh()
            repository.loadMore()

            repository.refresh()

            assertEquals(listOf(null, "c1", null), api.requestedCursors)
            assertEquals(listOf("1"), dao.observeAll().first().map { it.id })
        }

    @Test
    fun `only the first page is cached, however many are loaded`() =
        runTest {
            repository.refresh()
            repository.loadMore()

            assertEquals(listOf("1"), dao.observeAll().first().map { it.id })
        }

    @Test
    fun `it asks for the documented page size`() =
        runTest {
            repository.refresh()

            assertEquals(listOf(PAGE_SIZE), api.requestedLimits)
        }

    @Test
    fun `entryForMedia returns null for a title that is not in the library`() =
        runTest {
            // The backend answers an empty page, not a 404 (decision C-C). Null here is what
            // draws the detail screen's Add button rather than Edit.
            api.enqueueLibraryPage(LibraryPageDto(items = emptyList(), nextCursor = null))

            assertNull(repository.entryForMedia("media-1"))
            assertEquals("media-1", api.requestedMediaIds.last())
        }

    @Test
    fun `a non-default filter is NOT written to the cache`() =
        runTest {
            // Decision C-B. Caching a filtered page would make a later cold start render
            // "Watching" as if it were the whole library.
            api.enqueueLibraryPage(pageOf("Cached title"))
            repository.refresh()
            api.enqueueLibraryPage(pageOf("Only planned"))

            repository.applyFilter(LibraryFilter(status = UserMediaStatus.PLANNED))

            assertEquals(listOf("Cached title"), dao.observeAll().first().map { it.title })
        }

    @Test
    fun `applying a filter sends it as query parameters and starts from page one`() =
        runTest {
            api.enqueueLibraryPage(pageOf("Anything", nextCursor = "cursor-2"))
            repository.refresh()
            api.enqueueLibraryPage(pageOf("Filtered"))

            repository.applyFilter(LibraryFilter(status = UserMediaStatus.WATCHING, sort = LibrarySort.SCORE))

            assertEquals("watching", api.requestedStatuses.last())
            assertEquals("score", api.requestedSorts.last())
            // The cursor from the PREVIOUS filter must not carry over: replaying it under a new
            // sort compares against the wrong column and silently skips rows.
            assertNull(api.requestedCursors.last())
        }

    /**
     * The read half of decision C-B, which `a non-default filter is NOT written to the cache`
     * does not reach: that test asserts on `dao.observeAll()` directly, never on
     * `observeLibrary()`, so it cannot see whether the `combine`'s `else paged` branch (as
     * opposed to `paged.ifEmpty { cached }`) is actually wired in. Without it, an empty filtered
     * result would render the stale unfiltered cache instead of an empty list — the exact failure
     * C-B exists to prevent, and the reason `filter` is a third source of the `combine` at all.
     */
    @Test
    fun `observeLibrary shows an empty filtered result, not the unfiltered cache`() =
        runTest {
            api.enqueueLibraryPage(pageOf("Cached title"))
            repository.refresh()
            api.enqueueLibraryPage(LibraryPageDto(items = emptyList(), nextCursor = null))

            repository.applyFilter(LibraryFilter(status = UserMediaStatus.PLANNED))

            assertEquals(emptyList<String>(), repository.observeLibrary().first().map { it.media.title })
        }

    /**
     * The invariant `applyFilter`'s KDoc names: `filter` must always describe the filter
     * [paginator]'s current contents actually came from. `CursorPaginator.restart` fetches before
     * it mutates, so a failed `applyFilter` must leave `filter` pointing at whatever the
     * paginator still holds — the OLD filter — not the new one that never took effect. Getting
     * this backwards is exactly task 9a.4's `MediaRepositoryImpl.search` bug: a later `loadMore`
     * would pair the new filter with the old cursor.
     */
    @Test
    fun `a failed applyFilter restores the previous filter rather than leaving it mismatched`() =
        runTest {
            api.failNext()

            runCatching { repository.applyFilter(LibraryFilter(status = UserMediaStatus.PLANNED)) }
            repository.refresh()

            // First call: the failed attempt DID try "planned" — proving the filter was applied
            // before the fetch ran. Second call: refresh() went out under the RESTORED (default)
            // filter, not "planned" — proving the rollback happened.
            assertEquals(listOf("planned", null), api.requestedStatuses)
        }

    @Test
    fun `an unrated score is sent as an explicit null, not omitted`() =
        runTest {
            // The whole reason the PATCH body is a JsonObject. An omitted score means "leave it
            // alone", so a data class with a nullable field could never express "unrate this".
            api.enqueueEntry(entryBody())

            repository.update("entry-1", LibraryPatch(score = ScoreChange.Clear))

            val (id, patch) = api.updateRequests.single()
            assertEquals("entry-1", id)
            assertTrue(patch.containsKey("score"))
            assertEquals(JsonNull, patch.getValue("score"))
        }

    /**
     * `JsonObjectBuilder.put` overloads on both `String` and `Number`, so
     * `put("score", change.value.toDouble())` compiles, passes the null/omitted-key tests above,
     * and silently reintroduces the IEEE-754 drift `NUMERIC(3,1)` and "BigDecimal, never Double"
     * exist to prevent. `isString` is asserted explicitly — a value-only assertion (`content ==
     * "8.5"`) would still pass for the JSON NUMBER `8.5`, since `JsonPrimitive.content` stringifies
     * either kind. `isString` is the only thing that pins WHICH kind was actually written.
     */
    @Test
    fun `a set score is sent as a JSON string, not a number`() =
        runTest {
            api.enqueueEntry(entryBody())

            repository.update("entry-1", LibraryPatch(score = ScoreChange.Set(BigDecimal("8.5"))))

            val (_, patch) = api.updateRequests.single()
            val score = patch.getValue("score") as JsonPrimitive
            assertTrue(score.isString)
            assertEquals("8.5", score.content)
        }

    @Test
    fun `a patch sends only the fields it names`() =
        runTest {
            api.enqueueEntry(entryBody())

            repository.update("entry-1", LibraryPatch(progress = 12))

            val (_, patch) = api.updateRequests.single()
            assertEquals(12, patch.getValue("progress").jsonPrimitive.int)
            // If this fails, every progress edit is also silently resetting the score.
            assertFalse(patch.containsKey("score"))
            assertFalse(patch.containsKey("status"))
            // The read half of `update()`'s single-row upsert: without `dao.insertAll(...)` in the
            // implementation, the edit would reach the server but never the cache the cold-start
            // path reads from.
            assertEquals(listOf(12), dao.observeAll().first().map { it.progress })
        }

    @Test
    fun `adding a title refreshes the list so it appears`() =
        runTest {
            // Decision C-K. Without the refresh the user returns from detail to a list that does
            // not contain what they just added.
            api.enqueueEntry(entryBody(title = "Newly added"))
            api.enqueueLibraryPage(pageOf("Newly added"))

            val created = repository.add(MediaSource.ANILIST, "154587")

            assertEquals("Newly added", created.media.title)
            assertEquals(listOf("Newly added"), dao.observeAll().first().map { it.title })
            // The source enum is lowercased for the wire, matching what `library()`'s status/sort
            // params do above.
            assertEquals(AddLibraryEntryRequest(source = "anilist", externalId = "154587"), api.addRequests.single())
        }

    private fun dto(
        id: String,
        score: String? = null,
        title: String = "Title $id",
    ) = LibraryEntryDto(
        id = id,
        status = "watching",
        score = score,
        progress = 12,
        favorite = true,
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
    )

    /** A one-item page, for the filter/write tests that do not care about a real cursor chain. */
    private fun pageOf(
        title: String,
        nextCursor: String? = null,
    ): LibraryPageDto = LibraryPageDto(items = listOf(dto(id = "id-$title", title = title)), nextCursor = nextCursor)

    /** A single entry DTO, for stubbing `addLibraryEntry`/`updateLibraryEntry` responses. */
    private fun entryBody(title: String = "Title entry-1"): LibraryEntryDto = dto(id = "entry-1", title = title)

    private companion object {
        const val PAGE_SIZE = 20
    }
}

/**
 * Hand-written rather than MockWebServer: what is under test here is the repository's own
 * sequencing — which cursor it asks for, and when — and recording the arguments directly is what
 * makes that assertable. `:core:network`'s own tests already cover the wire.
 */
private class FakeShowTrackApi(
    private val pages: Map<String?, LibraryPageDto>,
) : ShowTrackApi {
    val requestedCursors = mutableListOf<String?>()
    val requestedLimits = mutableListOf<Int>()
    val requestedStatuses = mutableListOf<String?>()
    val requestedSorts = mutableListOf<String?>()
    val requestedMediaIds = mutableListOf<String?>()
    val addRequests = mutableListOf<AddLibraryEntryRequest>()
    val updateRequests = mutableListOf<Pair<String, JsonObject>>()
    private var shouldFail = false

    // One-shot responses, consumed in FIFO order and taking priority over [pages] — the
    // equivalent of MockWebServer's `enqueue`, needed because [pages] is keyed by cursor alone
    // and several filter/write tests issue more than one `cursor = null` request that must answer
    // differently each time.
    private val queuedPages = ArrayDeque<LibraryPageDto>()
    private val queuedEntries = ArrayDeque<LibraryEntryDto>()

    fun failNext() {
        shouldFail = true
    }

    fun enqueueLibraryPage(page: LibraryPageDto) {
        queuedPages.addLast(page)
    }

    fun enqueueEntry(entry: LibraryEntryDto) {
        queuedEntries.addLast(entry)
    }

    override suspend fun library(
        cursor: String?,
        limit: Int,
        status: String?,
        sort: String?,
        mediaId: String?,
    ): LibraryPageDto {
        requestedCursors += cursor
        requestedLimits += limit
        requestedStatuses += status
        requestedSorts += sort
        requestedMediaIds += mediaId
        if (shouldFail) {
            shouldFail = false
            throw IOException("simulated network failure")
        }
        queuedPages.removeFirstOrNull()?.let { return it }
        return pages.getValue(cursor)
    }

    // The rest of the interface. `error(...)` rather than a silent no-op: a library test that
    // reached these would be doing something it has no business doing, and should say so loudly.
    // PushRepositoryImplTest has its own fake for the push half; the search/detail methods stay
    // outside this repository's business.
    override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto {
        addRequests += request
        return queuedEntries.removeFirst()
    }

    override suspend fun updateLibraryEntry(
        id: String,
        patch: JsonObject,
    ): LibraryEntryDto {
        updateRequests += id to patch
        return queuedEntries.removeFirst()
    }

    override suspend fun searchMedia(
        query: String,
        page: Int,
    ): MediaSearchResponseDto = error("this fake only serves observeLibrary/refresh/loadMore")

    override suspend fun mediaDetail(id: String): MediaDto =
        error("this fake only serves observeLibrary/refresh/loadMore")

    override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto =
        error("the library repository must not touch push registration")

    override suspend fun deletePushTarget(id: String): Unit =
        error("the library repository must not touch push registration")
}
