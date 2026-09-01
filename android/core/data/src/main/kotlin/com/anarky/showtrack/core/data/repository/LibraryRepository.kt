package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.LibraryEntry
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
}
