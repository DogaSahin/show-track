package com.anarky.showtrack.core.model

/**
 * A search result. Deliberately has NO id: search writes nothing, so no row exists to have one,
 * and identity is `(source, externalId)` (decision C-N). This is why a search result cannot open
 * the detail screen directly — `POST /v1/library` is what mints the id.
 */
data class MediaSummary(
    val source: MediaSource,
    val externalId: String,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val coverImageUrl: String?,
)
