package com.anarky.showtrack.core.model

/** Mirrors the backend's per-provider health for one search. */
enum class SourceStatus { OK, TIMEOUT, RATE_LIMITED, ERROR, NOT_CONFIGURED, UNKNOWN }

/**
 * [degraded] is why this is not just a `List<MediaSummary>`. The backend reports `has_more: false`
 * when the providers that ANSWERED have no more — so a TMDB timeout looks exactly like "that is
 * all there is" unless the client reads the per-source map (decision C-O).
 */
data class SearchResults(
    val items: List<SearchResult>,
    val hasMore: Boolean,
    val degraded: List<MediaSource>,
) {
    val isDegraded: Boolean get() = degraded.isNotEmpty()

    companion object {
        val EMPTY = SearchResults(items = emptyList(), hasMore = false, degraded = emptyList())
    }
}

/**
 * One search result and what the signed-in user already has of it. [mediaId] is set when the
 * title is stored, so it can be opened without resolving it first; [libraryStatus] is set when the
 * user tracks it (null for an unknown status as well as for "not tracked": the screen then offers
 * Add, which is idempotent, rather than guessing a status).
 */
data class SearchResult(
    val media: MediaSummary,
    val mediaId: String? = null,
    val libraryStatus: UserMediaStatus? = null,
)
