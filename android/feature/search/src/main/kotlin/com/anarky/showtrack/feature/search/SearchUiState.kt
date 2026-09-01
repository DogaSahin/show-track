package com.anarky.showtrack.feature.search

import com.anarky.showtrack.core.model.SearchResults

/**
 * The search screen's state. A closed sealed hierarchy rather than a bag of booleans, for the same
 * reason [com.anarky.showtrack.feature.library.LibraryUiState] is one: a `when` over this cannot
 * represent "loading AND showing an error AND holding stale results" all at once.
 *
 * This screen runs THREE independent operations — a fresh search, [loadMore][SearchViewModel.loadMore]
 * and [add][SearchViewModel.add] — and, per the carried-forward lesson from task 9a.8's shipped bug
 * (one shared error slot let a failed page-2 fetch discard a fully-populated screen), each gets its
 * own failure channel with its own blast radius:
 * - A **search** may legitimately replace [Success] with [Error] wholesale: it runs before there is
 *   a results list belonging to the NEW query, so there is nothing on screen yet that a failure would
 *   discard (the OLD query's list, if any, is for a different query and is not what the user is
 *   waiting on).
 * - A failed **loadMore** must leave [Success.results] standing — [pageError] surfaces it as a
 *   footer beside the list, mirroring `LibraryUiState.Success.pageError`.
 * - A failed **add** must also leave [Success.results] standing, and reports beside the ROW that
 *   failed via [addError] rather than over the whole screen — [AddFailure.externalId] is what tells
 *   the screen which row to annotate.
 */
sealed interface SearchUiState {
    /** No query has been typed yet. [com.anarky.showtrack.core.designsystem.component.EmptyState] renders
     * "type to search" here, distinct from [Success] with an empty [SearchResults.items] ("no results"). */
    data object Idle : SearchUiState

    /** The first page of a NEW query is in flight. Replaces whatever was on screen before it. */
    data object Loading : SearchUiState

    /**
     * [adding] holds the `externalId` of the result currently being added, so exactly that row shows
     * a spinner rather than the whole list — `null` means nothing is in flight.
     *
     * [loadingMore] and [pageError] are [loadMore][SearchViewModel.loadMore]'s own channel;
     * [addError] is [add][SearchViewModel.add]'s. Kept as three separate fields, never one shared
     * slot — see the class KDoc for why sharing one was the actual bug a previous screen shipped.
     */
    data class Success(
        val results: SearchResults,
        val adding: String? = null,
        val loadingMore: Boolean = false,
        val pageError: Throwable? = null,
        val addError: AddFailure? = null,
    ) : SearchUiState

    /** Only a failed SEARCH ever produces this — a failed loadMore or add never promotes here. */
    data class Error(
        val cause: Throwable,
    ) : SearchUiState
}

/**
 * One failed [SearchViewModel.add] call, tagged with the result it was for. A bare `Throwable?`
 * could not tell the screen which row to annotate once more than one result is on screen.
 */
data class AddFailure(
    val externalId: String,
    val cause: Throwable,
)
