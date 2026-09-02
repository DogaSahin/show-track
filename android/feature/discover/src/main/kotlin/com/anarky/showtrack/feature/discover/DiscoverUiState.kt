package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.model.Recommendation

/**
 * The discover screen's state. A closed sealed hierarchy rather than a bag of booleans, the same
 * reason [com.anarky.showtrack.feature.library.LibraryUiState]/
 * [com.anarky.showtrack.feature.search.SearchUiState] are: a `when` over this cannot represent
 * "loading AND showing an error AND holding a stale feed" all at once.
 *
 * This screen runs THREE independent operations — a fresh [DiscoverViewModel.refresh],
 * [DiscoverViewModel.loadMore] and [DiscoverViewModel.add] — and, per the carried-forward lesson
 * from task 9a.8's shipped bug (one shared error slot let a failed page-2 fetch discard a
 * fully-populated screen), each gets its own failure channel with its own blast radius (decision
 * C-S):
 * - A **refresh** (the initial load, or a retry from [Error]) may legitimately replace [Success]
 *   with [Error] wholesale — there is nothing on screen a fresh load's failure would discard that
 *   the user was still relying on.
 * - A failed **loadMore** must leave [Success.items] standing — [pageError] surfaces it as a
 *   footer beside the list, mirroring `LibraryUiState.Success.pageError`/`SearchUiState.Success.pageError`.
 * - A failed **add** must also leave [Success.items] standing (including the OPTIMISTICALLY
 *   REMOVED row restored to its original index — decision D-I) and reports beside the row that
 *   failed via [addError], rather than over the whole screen.
 */
sealed interface DiscoverUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : DiscoverUiState

    /**
     * [loadingMore] and [pageError] are [DiscoverViewModel.loadMore]'s own channel; [addError] is
     * [DiscoverViewModel.add]'s. Kept as three separate fields on this class, never one shared
     * slot — see the class KDoc for why sharing one was the actual bug a previous screen shipped.
     *
     * There is no per-row "adding" spinner field the way `SearchUiState.Success.adding` has one:
     * decision D-I removes the tapped row from [items] IMMEDIATELY, so there is no row left on
     * screen to show a spinner on while the add is in flight — the row's absence *is* the pending
     * state, until [addError] (a failure) or nothing at all (success) follows it.
     */
    data class Success(
        val items: List<Recommendation>,
        val loadingMore: Boolean = false,
        val pageError: Throwable? = null,
        val addError: AddFailure? = null,
    ) : DiscoverUiState

    /** Only a failed refresh ever produces this — a failed loadMore or add never promotes here. */
    data class Error(
        val cause: Throwable,
    ) : DiscoverUiState
}

/**
 * One failed [DiscoverViewModel.add] call, tagged with the row it was for. A bare `Throwable?`
 * could not tell the screen which row to annotate once more than one row is on screen — the same
 * reasoning as `com.anarky.showtrack.feature.search.AddFailure`.
 */
data class AddFailure(
    val mediaId: String,
    val cause: Throwable,
)
