package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One item of `GET /v1/media/search` (`SearchItem` on the backend): the summary fields plus what
 * the caller already has. The extras default, so a server without them still decodes.
 */
@Serializable
data class SearchItemDto(
    val source: String,
    @SerialName("external_id") val externalId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    @SerialName("cover_image_url") val coverImageUrl: String?,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("library_entry") val libraryEntry: LibraryEntryRefDto? = null,
)

@Serializable
data class LibraryEntryRefDto(
    val id: String,
    val status: String,
)

@Serializable
data class ResolveMediaRequestDto(
    val source: String,
    @SerialName("external_id") val externalId: String,
)

/**
 * `sources` is not diagnostic. `has_more: false` alongside a non-ok provider means "no more from
 * the providers that answered", not "no more results exist" — so the UI must read it to avoid
 * presenting half an answer as the whole one (decision C-O). Kept as raw strings here; mapping to
 * an enum happens in :core:data, so an unknown provider or status cannot crash the decode.
 */
@Serializable
data class MediaSearchResponseDto(
    val items: List<SearchItemDto>,
    val page: Int,
    @SerialName("has_more") val hasMore: Boolean,
    val sources: Map<String, String>,
)
