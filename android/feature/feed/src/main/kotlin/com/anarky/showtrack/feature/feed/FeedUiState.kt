package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupFailure

/**
 * The feed screen's state (task 9c.4) — [com.anarky.showtrack.feature.groups.GroupDetailUiState]'s
 * identical shape and identical reasoning, one screen over: a closed sealed hierarchy, not a bag
 * of booleans, so a `when` over this cannot represent "loading AND showing an error AND holding a
 * stale entry list" all at once.
 *
 * There is no fourth, "no active group" case here — see [FeedScreen]'s own KDoc for why that is a
 * fact [FeedUiState] never represents at all: [FeedViewModel] is simply never asked to load
 * anything until a group id exists (Ruling 1, `progress.md`), and the stateless [FeedScreen]
 * overload renders the no-groups empty state straight off its own `activeGroupId` parameter,
 * never off this type.
 */
sealed interface FeedUiState {
    /** The initial load for the active group, or a retry from [Error], is in flight. */
    data object Loading : FeedUiState

    /**
     * [loadingMore]/[pageError] are [FeedViewModel.loadMore]'s own channel — `LibraryUiState.Success`'s
     * identical shape: a page-2 fetch in flight, or one that just failed, still has a full, valid
     * [entries] list underneath it, which is exactly what a fourth sealed case would have to
     * duplicate for no behavioural difference. A page-fetch failure renders as a footer under the
     * list (Global Constraints: "a page-fetch failure is a footer, never a promotion to a
     * full-screen Error — uniform across Library, Discover, Favorites and the watchlist"), never
     * as [Error] — the entries already on screen are still valid and still worth showing.
     *
     * [isStale] is [FeedViewModel.refresh]'s own SEPARATE channel — decision C-S, and the exact
     * split [GroupDetailUiState.Success.watchlistPageError]'s own KDoc documents as a repeated
     * bug in this project: a `loadMore` retry only makes sense if there is a next page, but a
     * reload can fail on an already-exhausted list, where a footer wired to [FeedViewModel.loadMore]
     * alone would be a dead tap forever. A failed reload marks [entries] stale rather than blanking
     * or erroring them away — the settled refresh shape (Global Constraints) — and the next
     * successful reload clears it.
     */
    data class Success(
        val entries: List<FeedEntry>,
        val loadingMore: Boolean = false,
        val pageError: GroupFailure? = null,
        val isStale: Boolean = false,
    ) : FeedUiState

    /** Only a failed load with nothing already on screen for the active group ever produces this. */
    data class Error(
        val cause: GroupFailure,
    ) : FeedUiState
}
