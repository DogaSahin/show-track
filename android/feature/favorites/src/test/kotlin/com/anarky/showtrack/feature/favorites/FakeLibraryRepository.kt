package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/** One [FakeLibraryRepository.favoritesPage] call, as the ViewModel made it. */
internal data class PageRequest(
    val type: MediaType?,
    val sort: LibrarySort,
    val cursor: String?,
    val limit: Int,
)

/**
 * Answers [favoritesPage] from [pages], keyed by (type, cursor): the podium asks with no type, each
 * shelf with its own, and a later page with the cursor the previous one returned. A missing key is
 * an empty last page. [pageGate] holds every page request until completed, so a test can observe a
 * screen mid-fetch or race two requests; [updates] records every favourite toggle.
 */
internal class FakeLibraryRepository(
    val pages: MutableMap<Pair<MediaType?, String?>, Page<LibraryEntry>> = mutableMapOf(),
) : LibraryRepository {
    var pageFailure: Throwable? = null
    var pageGate: CompletableDeferred<Unit>? = null
    val pageRequests = mutableListOf<PageRequest>()

    var updateFailure: Throwable? = null
    var updateGate: CompletableDeferred<Unit>? = null
    val updates = mutableListOf<Pair<String, LibraryPatch>>()

    /** How many times the tab's refresh asked for the podium: one per refresh. */
    val podiumRequests: Int get() = pageRequests.count { it.type == null && it.cursor == null }

    override suspend fun favoritesPage(
        type: MediaType?,
        sort: LibrarySort,
        cursor: String?,
        limit: Int,
    ): Page<LibraryEntry> {
        pageRequests += PageRequest(type, sort, cursor, limit)
        pageGate?.await()
        pageFailure?.let { throw it }
        return pages[type to cursor] ?: Page(emptyList(), null)
    }

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry {
        updates += entryId to patch
        updateGate?.await()
        updateFailure?.let { throw it }
        // The ViewModels ignore the returned entry; any well-formed one will do.
        return favourite(id = entryId)
    }

    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by Favorites")

    override suspend fun refresh(): Unit = error("not exercised by Favorites")

    override suspend fun loadMore(): Unit = error("not exercised by Favorites")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by Favorites")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = error("not exercised by Favorites")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by Favorites")

    override suspend fun libraryStats(): LibraryStats = error("not exercised by Favorites")

    override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

    override suspend fun importAniList(username: String) = error("not exercised by Favorites")
}
