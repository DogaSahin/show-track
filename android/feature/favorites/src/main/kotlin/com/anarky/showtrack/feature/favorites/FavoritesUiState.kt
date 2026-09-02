package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The favourites screen's state. A closed sealed hierarchy rather than a bag of booleans, the
 * same reason `LibraryUiState`/`DiscoverUiState` are: a `when` over this cannot represent
 * "loading AND showing an error AND holding a stale list" all at once.
 *
 * There is no `isStale`/cache-fallback field the way `LibraryUiState.Success` has one: this
 * screen is network-only (decision C-U — see [FavoritesViewModel]'s KDoc), so there is never a
 * cached row to fall back to while a fresh fetch is in flight.
 */
sealed interface FavoritesUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : FavoritesUiState

    /**
     * [loadingMore] and [pageError] are [FavoritesViewModel.loadMore]'s own channel — a SEPARATE
     * one from a failed [FavoritesViewModel.refresh] (decision C-S), which promotes this whole
     * screen to [Error] instead. The entries a failed page-2 fetch left behind are still valid and
     * still on screen, so a page failure must never discard them — the same reasoning
     * `LibraryUiState.Success.pageError`/`DiscoverUiState.Success.pageError` carry.
     *
     * No `add`/`addError` field the way `DiscoverUiState.Success` has one: favouriting happens on
     * Detail or Library (task 9b.4's brief — "no add here"), so this screen has nothing of its own
     * to fail beyond loading and paging.
     */
    data class Success(
        val entries: List<LibraryEntry>,
        val loadingMore: Boolean = false,
        val pageError: Throwable? = null,
    ) : FavoritesUiState

    /** Only a failed [FavoritesViewModel.refresh] ever produces this — a failed loadMore never promotes here. */
    data class Error(
        val cause: Throwable,
    ) : FavoritesUiState
}
