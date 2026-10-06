package com.anarky.showtrack.feature.search

import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.SearchResults

/**
 * The search screen's state. A closed sealed hierarchy rather than a bag of booleans, so a `when`
 * over it cannot represent "loading AND showing an error AND holding stale results" at once.
 *
 * Each operation has its own failure channel, so one failing never discards another's work:
 * - A **search** may replace [Success] with [Error] wholesale: the list on screen belongs to the
 *   previous query, which is not what the user is waiting on.
 * - A failed **load more** leaves [Success.results] standing and sets [Success.pageError].
 * - A failed **add** or **open** leaves everything standing and is reported once, as a
 *   [SearchEvent] snackbar.
 */
sealed interface SearchUiState {
    /** Nothing to search for: the screen shows recent searches. */
    data object Idle : SearchUiState

    /** The first page of a new query is in flight. */
    data object Loading : SearchUiState

    /**
     * [adding] and [opening] hold the [key] of the one row with an add or an open in flight, so
     * only that row shows it.
     */
    data class Success(
        val results: SearchResults,
        val adding: String? = null,
        val opening: String? = null,
        val loadingMore: Boolean = false,
        val pageError: Throwable? = null,
    ) : SearchUiState

    /** Only a failed SEARCH ever produces this. */
    data class Error(
        val cause: Throwable,
    ) : SearchUiState
}

/** One-shot outcomes the screen turns into navigation or a snackbar. */
sealed interface SearchEvent {
    data class OpenDetail(
        val mediaId: String,
    ) : SearchEvent

    data class Added(
        val title: String,
        val mediaId: String,
    ) : SearchEvent

    data object AddFailed : SearchEvent

    data object OpenFailed : SearchEvent
}

/** A result's identity. Source and id together: the same external id can exist in both sources. */
internal val MediaSummary.key: String get() = "${source.name}:$externalId"
