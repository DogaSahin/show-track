package com.anarky.showtrack.core.model

/** Mirrors the backend's per-provider health for one search. */
enum class SourceStatus { OK, TIMEOUT, RATE_LIMITED, ERROR, NOT_CONFIGURED, UNKNOWN }

/**
 * [degraded] is why this is not just a `List<MediaSummary>`. The backend reports `has_more: false`
 * when the providers that ANSWERED have no more — so a TMDB timeout looks exactly like "that is
 * all there is" unless the client reads the per-source map (decision C-O).
 */
data class SearchResults(
    val items: List<MediaSummary>,
    val hasMore: Boolean,
    val degraded: List<MediaSource>,
) {
    val isDegraded: Boolean get() = degraded.isNotEmpty()

    companion object {
        val EMPTY = SearchResults(items = emptyList(), hasMore = false, degraded = emptyList())
    }
}
