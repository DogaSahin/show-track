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
     */
    data class Success(
        val entries: List<LibraryEntry>,
        val loadingMore: Boolean,
    ) : LibraryUiState

    data class Error(
        val cause: Throwable,
    ) : LibraryUiState
}
