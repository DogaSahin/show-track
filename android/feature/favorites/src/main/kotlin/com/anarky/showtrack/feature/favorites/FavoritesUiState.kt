package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The favourites screen's state. A closed sealed hierarchy rather than a bag of booleans, the
 * same reason `LibraryUiState`/`DiscoverUiState` are: a `when` over this cannot represent
 * "loading AND showing an error AND holding a stale list" all at once.
 *
 * There IS an `isStale` field on [Success], mirroring `LibraryUiState.Success`'s (review finding,
 * round 3 — an earlier version of this file argued the opposite, on the grounds that this screen
 * is network-only and therefore has no cache to fall back to). That reasoning stopped being true
 * the moment [FavoritesViewModel.refresh] stopped blanking [Success] to [Loading] on every resume
 * (round 2's fix): from round 2 onward, a resume over a populated screen keeps the PREVIOUSLY
 * FETCHED list on screen for the whole round trip, which is exactly the shape decision C-B is
 * about — the fallback is the last live fetch rather than a Room row, but the "are these rows
 * still current?" question, and the banner that answers it, apply identically.
 */
sealed interface FavoritesUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : FavoritesUiState

    /**
     * [loadingMore] and [pageError] are [FavoritesViewModel.loadMore]'s own channel — a SEPARATE
     * one from a failed [FavoritesViewModel.refresh] (decision C-S), which promotes this whole
     * screen to [Error] instead, UNLESS this is already [Success] — see [FavoritesViewModel.refresh]'s
     * own KDoc for why a resume's failure must not destroy a screen the user was already reading.
     * The entries a failed page-2 fetch left behind are still valid and still on screen either way,
     * so a page failure must never discard them — the same reasoning
     * `LibraryUiState.Success.pageError`/`DiscoverUiState.Success.pageError` carry.
     *
     * [isStale] is decision C-B made real here, the same way `LibraryUiState.Success.isStale` is:
     * true when a resume's [FavoritesViewModel.refresh] has failed and these [entries] are the last
     * ones that loaded successfully, not a guarantee anything actually changed server-side. Cleared
     * the moment a later fetch succeeds. Driven by [FavoritesScreen]'s `StaleDataBanner`, which sits
     * ABOVE the list rather than replacing it — see [FavoritesViewModel.refresh]'s KDoc for why
     * showing the old rows UNMARKED (what a naive fix would do) is worse than a full-screen error,
     * and why a full-screen error is in turn worse than the old rows marked stale.
     *
     * No `add`/`addError` field the way `DiscoverUiState.Success` has one: favouriting happens on
     * Detail or Library (task 9b.4's brief — "no add here"), so this screen has nothing of its own
     * to fail beyond loading and paging.
     */
    data class Success(
        val entries: List<LibraryEntry>,
        val loadingMore: Boolean = false,
        val pageError: Throwable? = null,
        val isStale: Boolean = false,
    ) : FavoritesUiState

    /** Only a failed [FavoritesViewModel.refresh] with nothing already on screen ever produces this. */
    data class Error(
        val cause: Throwable,
    ) : FavoritesUiState
}
