package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.Recommendation
import kotlinx.coroutines.flow.StateFlow

/**
 * The only data-layer type `:feature:discover` sees (architecture rule 2). Network-only, unlike
 * [LibraryRepository]: there is no Room-backed cache behind this feed — a recommendation is not
 * something the user owns, it exists only to be acted on once, and caching it would keep a row the
 * user already added sitting stale in a local table for no reason.
 */
interface RecommendationRepository {
    /** The accumulated pages fetched so far, in ranked order — the ranking IS the score (7-K). */
    val feed: StateFlow<List<Recommendation>>

    /** Reload from the first page, replacing whatever [feed] currently holds. */
    suspend fun refresh()

    /** Appends the next page, or does nothing once the feed is exhausted. */
    suspend fun loadMore()

    /**
     * Removes the row for [mediaId] from [feed], in memory only — there is no server-side
     * "dismiss" endpoint for a recommendation. Decision D-I's optimistic add calls this
     * immediately, before the `POST /v1/library` round trip even starts; if that call fails, the
     * VIEWMODEL is what re-inserts the row (at its original index) into what the screen renders —
     * this function only ever removes, since only the caller still holds the removed row and where
     * it was.
     */
    fun remove(mediaId: String)
}
