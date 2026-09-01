package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow

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
}
