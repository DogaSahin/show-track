package com.anarky.showtrack.core.model

/**
 * `POST /v1/library/import/anilist`'s result (task 9b.6, backend decision 4-H — synchronous, no
 * task table to poll).
 *
 * [truncated] is what makes a list cut off at the backend's per-import chunk cap distinguishable
 * from a complete one (decision 4-L): without it, a truncated response is byte-identical to a
 * complete one and the screen would report success when it only imported a prefix of the list.
 * `false` is what a complete import reports, never an unknown or absent state.
 */
data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val truncated: Boolean,
)
