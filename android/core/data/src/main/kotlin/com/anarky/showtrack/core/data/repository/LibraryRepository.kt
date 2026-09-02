package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only data-layer type any `:feature:*` module ever sees. Everything behind it — Retrofit,
 * Room, the DTOs, the entities — is `implementation`-scoped inside `:core:data` and invisible
 * from a feature's compile classpath, which is architecture rule 2 made structural rather than
 * conventional.
 *
 * There is no use-case layer between this and a ViewModel by decision: a use case per method
 * would be one class each forwarding a single call.
 */
interface LibraryRepository {
    /** Cold-start content, from the cache, then whatever [refresh] last wrote over it. */
    fun observeLibrary(): Flow<List<LibraryEntry>>

    /** Discards the paged state and re-reads from the first page. */
    suspend fun refresh()

    /** Appends the next page, or does nothing once the list is exhausted. */
    suspend fun loadMore()

    /** Re-queries from page one under [filter]. Only the default filter is cached (decision C-B). */
    suspend fun applyFilter(filter: LibraryFilter)

    /** `POST /v1/library`, then refreshes so the new title appears in the list (decision C-K). */
    suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry

    suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry

    /** Null means "not in your library" — not an error (decision C-C). */
    suspend fun entryForMedia(mediaId: String): LibraryEntry?

    /**
     * `GET /v1/library?favorite=true`'s accumulated pages, for `:feature:favorites` (task 9b.4,
     * decision D-H). Backed by its OWN [com.anarky.showtrack.core.data.paging.CursorPaginator]
     * instance, entirely separate from the one behind [observeLibrary]/[refresh]/[loadMore]/
     * [applyFilter]: Library and Favorites are both `TopLevelDestination`s with saved state and
     * can be open at once, so sharing one paginator would make switching tabs reset the OTHER
     * screen's scroll position and page counter.
     *
     * Network-only, no Room cache — see [LibraryRepositoryImpl]'s KDoc on the field backing this.
     */
    val favoriteEntries: StateFlow<List<LibraryEntry>>

    /** Reload the favourites view from the first page, replacing whatever [favoriteEntries] holds. */
    suspend fun refreshFavorites()

    /** Appends the next page of [favoriteEntries], or does nothing once it is exhausted. */
    suspend fun loadMoreFavorites()

    /**
     * `GET /v1/library/stats` (task 9b.5, decision D-F's stats half). No cache and no `StateFlow`
     * upstream, unlike [observeLibrary]/[favoriteEntries] — this is a one-shot read the caller
     * (`ProfileViewModel`) drives itself and re-issues on its own schedule, the same shape
     * [add]/[update]/[entryForMedia] already have.
     */
    suspend fun libraryStats(): LibraryStats
}
