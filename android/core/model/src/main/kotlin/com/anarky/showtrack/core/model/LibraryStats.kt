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
 */
data class LibraryStats(
    val total: Int,
    val byStatus: Map<UserMediaStatus, Int>,
    val averageScore: BigDecimal?,
    val ratedCount: Int,
)
