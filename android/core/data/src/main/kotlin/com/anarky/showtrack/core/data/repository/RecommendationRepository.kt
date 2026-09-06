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
     * immediately, before the `POST /v1/library` round trip even starts; if that call fails,
     * [restore] is what puts the row back.
     */
    fun remove(mediaId: String)

    /**
     * The counterpart to [remove]: re-inserts [recommendation] into [feed] at [index] (clamped to
     * the current bounds), for a failed optimistic add. This lives on the REPOSITORY, not the
     * caller's own copy of the list, deliberately: [loadMore] re-publishes [feed] wholesale from
     * what it just fetched, so a restore applied only to a ViewModel's local state would be silently
     * discarded the next time [loadMore] succeeds — the row would vanish with no user action, and
     * any error still pointing at it would now name a row [feed] no longer contains. Routing the
     * restore through here instead keeps [feed] the single list [loadMore] appends onto, so a row
     * put back by [restore] survives every later [loadMore] the same way a row taken out by
     * [remove] stays out.
     *
     * [index] is ABSOLUTE, not relative to any other in-flight operation: restoring two
     * concurrently-removed rows out of ascending index order re-inserts the later one at a
     * position computed against a [feed] that no longer matches what [index] was captured
     * against, landing it in the wrong place. Callers restoring more than one row back must do so
     * lowest index first. This repository does not enforce that itself — today it is safe only
     * because `DiscoverViewModel.addInFlight` permits exactly one outstanding remove/restore pair
     * at a time, a guarantee that lives in the CALLER, not here; a future caller with per-row
     * in-flight state (or a second caller of this repository) would need to preserve it.
     */
    fun restore(
        index: Int,
        recommendation: Recommendation,
    )
}
