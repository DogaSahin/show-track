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
    // Room is the RENDER path, never the truth. It emits immediately on cold start so the screen
    // has content; refresh() overwrites it from the network. Nothing reads the cache to DECIDE.
    override fun observeLibrary(): Flow<List<LibraryEntry>> =
        dao.observeAll().map { entities -> entities.map(LibraryEntryEntity::toDomain) }

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
