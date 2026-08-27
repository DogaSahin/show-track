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

private const val PAGE_SIZE = 20

/**
 * Must be held as a singleton: [paginator] carries the cursor and the accumulated pages as
 * instance state, so a per-consumer instance would restart pagination for every collector.
 */
class LibraryRepositoryImpl(
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
     * `ifEmpty` also gives the right behaviour after a FAILED refresh: [refresh] resets the
     * paginator before fetching, so a fetch that throws leaves it empty and the screen falls back
     * to the stale-but-useful cache rather than blanking.
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
     */
    override fun observeLibrary(): Flow<List<LibraryEntry>> =
        combine(
            dao.observeAll().map { entities -> entities.map(LibraryEntryEntity::toDomain) },
            paginator.items,
        ) { cached, paged -> paged.ifEmpty { cached } }.distinctUntilChanged()

    private val paginator =
        CursorPaginator<LibraryEntry> { cursor ->
            val page = api.library(cursor = cursor, limit = PAGE_SIZE)
            Page(page.items.map(LibraryEntryDto::toDomain), page.nextCursor)
        }

    override suspend fun refresh() {
        paginator.reset()
        paginator.loadMore()
        // Only the FIRST page is cached. The cache exists so a cold start renders instantly, not
        // to mirror the API — persisting every page would make Room the thing you scroll, which is
        // the source-of-truth inversion rule 2 exists to prevent.
        dao.replaceAll(paginator.items.value.map(LibraryEntry::toEntity))
    }

    override suspend fun loadMore() = paginator.loadMore()
}
