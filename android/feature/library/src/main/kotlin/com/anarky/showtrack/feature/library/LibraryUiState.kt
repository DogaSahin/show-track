package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The library screen's state. Deliberately a closed sealed hierarchy rather than a bag of
 * booleans (`isLoading`, `error: Throwable?`, `entries: List<LibraryEntry>` all on one object) —
 * a boolean combination can represent an impossible state (loading AND showing an error AND
 * holding stale entries all at once); a `when` over this cannot.
 *
 * There is no separate "selected tab/sort" field here on purpose — see
 * [LibraryViewModel.filter]'s KDoc for why that lives outside this hierarchy entirely.
 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    /**
     * [loadingMore] is a field on [Success], not a fourth sealed case: a page-2 fetch in flight
     * still has a full, valid list of entries to show underneath its footer spinner, which is
     * exactly what [Success] already models. A dedicated `LoadingMore` case would have to carry
     * the same [entries] anyway and would fork every exhaustive `when` in the screen for no
     * behavioural difference.
     *
     * [pageError] is the same reasoning applied to a FAILED `loadMore()`: the entries a page-2
     * fetch failed underneath are still valid and still on screen (`LibraryRepository.loadMore`
     * leaves `paginator.items` untouched on a throw), so a page-fetch failure must never promote
     * this whole screen to [Error] — that would discard a fully-populated list over one failed
     * next page. It surfaces here instead, as a footer the list itself renders, and is cleared the
     * next time a page fetch succeeds (or a fresh filter is applied — see
     * `LibraryViewModel.applyCurrentFilter`'s KDoc).
     */
    data class Success(
        val entries: List<LibraryEntry>,
        val loadingMore: Boolean,
        val pageError: Throwable? = null,
        /**
         * True when the network has not answered SUCCESSFULLY yet, this session, on the default
         * filter, and there was something to show anyway — NOT a guarantee [entries] actually
         * came from the Room cache, even though that is the common case. `LibraryViewModel.state`
         * infers this from "has the repository's guard ever completed one call without throwing",
         * which is honest about freshness but blind to WHICH branch `observeLibrary()` took to
         * produce these particular [entries] — see `LibraryViewModel.mutableLoadState`'s KDoc for
         * the one known gap this leaves (a same-repository success from another screen). Drives
         * the banner - stale rows must never present as unconditionally live ones.
         */
        val isStale: Boolean = false,
    ) : LibraryUiState

    data class Error(
        val cause: Throwable,
    ) : LibraryUiState
}
