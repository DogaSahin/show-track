package com.anarky.showtrack.core.model

import java.math.BigDecimal

/**
 * `GET /v1/library/stats` (task 9b.2), aggregated server-side rather than paged client-side —
 * see the backend's own `get_stats` KDoc: the client holds one page of the library at a time
 * (decision C-B), and re-downloading everything to compute four numbers gets worse as the
 * library grows.
 *
 * [byStatus] carries only the statuses that actually occur — a status with zero entries is
 * ABSENT from the map, never present with a zero, because the server's `GROUP BY` only ever
 * produces rows for statuses that occur. Rendering an absent key as zero is a client-side
 * invention the server never asked for.
 *
 * [averageScore] is `BigDecimal`, NEVER `Double` — the wire field is a JSON STRING for the same
 * reason [LibraryEntry.score] is (backend decision 4-N): a JSON number is an IEEE 754 double, and
 * this is a NUMERIC average. Null when nothing is rated — a null average is a different and true
 * statement from a zero one ("nothing rated" vs. "you rate everything zero").
 *
 * The server already applies `ROUND(avg, 1)`, so this value's precision is final — nothing
 * downstream re-rounds or re-scales it.
 *
 * [ratedCount] travels with [averageScore] so a caller can say what the average is an average OF
 * ("8.4 across 12 rated titles"), not just the bare number.
 *
 * [episodesWatched] is the sum of every entry's progress, not a count of episodes that exist: a
 * library of planned titles reports zero.
 *
 * [topGenres] is an ORDERED list, not a map — the ranking is the information, and a `Map`'s
 * iteration order is not something the wire format guarantees. Already capped and tie-broken
 * server-side; nothing here re-sorts it.
 *
 * [addedThisMonth] counts the user's own add ACTIONS in the current UTC month, read off the
 * activity log. An AniList import contributes nothing to it (backend decision S-A writes one row
 * for N titles), which is a definition rather than an omission — see the backend's own
 * `_added_this_month`.
 */
data class LibraryStats(
    val total: Int,
    val byStatus: Map<UserMediaStatus, Int>,
    val averageScore: BigDecimal?,
    val ratedCount: Int,
    val episodesWatched: Int,
    val topGenres: List<GenreCount>,
    val addedThisMonth: Int,
    val favorites: Int,
)

/** One row of [LibraryStats.topGenres]. A type rather than a `Pair` so both halves are named. */
data class GenreCount(
    val genre: String,
    val count: Int,
)
