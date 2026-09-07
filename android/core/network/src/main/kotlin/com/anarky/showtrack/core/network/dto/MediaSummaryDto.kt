package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A search result. Carries NO `id`, deliberately: search writes nothing, so there is no row to
 * have one, and identity is `(source, external_id)` (decision C-N).
 */
@Serializable
data class MediaSummaryDto(
    val source: String,
    @SerialName("external_id") val externalId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    @SerialName("cover_image_url") val coverImageUrl: String?,
)

/**
 * `sources` is not diagnostic. `has_more: false` alongside a non-ok provider means "no more from
 * the providers that answered", not "no more results exist" — so the UI must read it to avoid
 * presenting half an answer as the whole one (decision C-O). Kept as raw strings here; mapping to
 * an enum happens in :core:data, so an unknown provider or status cannot crash the decode.
 */
@Serializable
data class MediaSearchResponseDto(
    val items: List<MediaSummaryDto>,
    val page: Int,
    @SerialName("has_more") val hasMore: Boolean,
    val sources: Map<String, String>,
)
