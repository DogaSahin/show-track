package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.mapper.toEntity
import com.anarky.showtrack.core.data.paging.CursorPaginator
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20

/**
 * `@Singleton` here on the CLASS rather than on `DataModule`'s `@Binds` method: scoping the bind
 * would scope only the [LibraryRepository] interface, leaving anyone who injected this type
 * concretely with a second instance. The scope belongs to the type because the state does —
 * [paginator] carries the cursor and the accumulated pages as instance state, so a per-consumer
 * instance would restart pagination from page one for every collector.
 */
@Singleton
class LibraryRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
        private val dao: LibraryDao,
    ) : LibraryRepository {
        /**
         * Room is the RENDER path, never the truth. Before the first fetch answers, [paginator] is
         * empty and the screen renders from the cache instantly; once the network has answered, the
         * paged list takes over WHOLESALE. Taking it wholesale rather than merging is what makes this
         * safe — there is no dedup to get wrong and no window in which a row shows up twice — and it
         * is also what makes [loadMore] visible, which it is not if this returns the DAO flow alone:
         * the cache is deliberately first-page-only, so pages 2..n exist nowhere else.
         *
         * `ifEmpty` is the cold-start fallback only. It is deliberately NOT the failure path:
         * `CursorPaginator.restart` fetches before it mutates, so a failed refresh leaves the paged
         * list standing and the user keeps the rows they were looking at. An earlier version cleared
         * the paginator first, and the cost was visible — refreshing a 40-row list emitted the 20-row
         * stale cache for the whole network round trip and then re-expanded, so a pull-to-refresh
         * jumped twice and lost its scroll position.
         *
         * One inherent edge, recorded so it is not mistaken for a regression: `ifEmpty` is a
         * sentinel, so an EMPTY first page is indistinguishable from "nothing fetched yet". If a
         * refresh legitimately returns zero rows — the user emptied their library on another device
         * — this momentarily falls back to the stale cache until the `dao.replaceAll` a line later
         * clears it. The flicker is inherent to the sentinel, not to the fetch-before-mutate change,
         * and behaves identically either way. Distinguishing the two states properly would mean
         * carrying an explicit "has loaded" signal out of the paginator; not worth it for a
         * single-frame flicker on an empty library, but that is the fix if it ever matters.
         *
         * Note the asymmetry this surfaces, because it is easy to trip over: an entry that has been
         * through the cache is NOT `==` to the same entry straight off the wire. The API sends
         * `updated_at` with microsecond precision and `library_entries.updated_at` is INTEGER epoch
         * millis, so a round trip through SQLite truncates it. Harmless — the field feeds ordering
         * and display, never equality — but a mapper round trip is not a cache round trip, and a test
         * that assumes otherwise is asserting the mappers against themselves.
         *
         * `distinctUntilChanged` is not tidying. [combine] re-emits on EVERY emission of EITHER
         * source, and `refresh()` moves both — it loads the paginator and then writes the cache — so
         * without it a refresh delivers the identical list twice and a `LazyColumn` recomposes for
         * the second one. It restores the conflation-by-equality a `StateFlow` would have given for
         * free and [combine] drops; the cost is one structural comparison of a page-sized list.
         *
         * Its reach is narrower than that makes it sound, and the `updatedAt` asymmetry above is why.
         * It suppresses the duplicate emission of ONE list — the paged one, which wins both times
         * whenever it is non-empty. It cannot conflate a cache-sourced row with a wire-sourced one:
         * those differ in the microsecond field and are never `==`. Duplicates, not equivalents.
         */
        override fun observeLibrary(): Flow<List<LibraryEntry>> =
            combine(
                dao.observeAll().map { entities -> entities.map(LibraryEntryEntity::toDomain) },
                paginator.items,
            ) { cached, paged -> paged.ifEmpty { cached } }.distinctUntilChanged()

        private val paginator =
            CursorPaginator<LibraryEntry> { cursor ->
                val page = api.library(cursor = cursor, limit = PAGE_SIZE, status = null, sort = null, mediaId = null)
                Page(page.items.map(LibraryEntryDto::toDomain), page.nextCursor)
            }

        override suspend fun refresh() {
            // ONE call, not `reset()` then `loadMore()`. Those take the paginator's lock twice, and a
            // scroll-triggered loadMore() landing in the gap would fetch page 1 itself and leave this
            // refresh fetching page 2 — after which the snapshot below holds two pages and the
            // first-page-only invariant is broken. The repository is a @Singleton with no dispatcher
            // confinement, so a pull-to-refresh overlapping a scroll is the ordinary case.
            val firstPage = paginator.restart()
            // The RETURNED page, never a re-read of `paginator.items.value`: that list can have grown
            // by the time we look at it, which is the same race one step further out.
            //
            // Only the FIRST page is cached. The cache exists so a cold start renders instantly, not
            // to mirror the API — persisting every page would make Room the thing you scroll, which is
            // the source-of-truth inversion rule 2 exists to prevent.
            dao.replaceAll(firstPage.map(LibraryEntry::toEntity))
        }

        override suspend fun loadMore() = paginator.loadMore()
    }
