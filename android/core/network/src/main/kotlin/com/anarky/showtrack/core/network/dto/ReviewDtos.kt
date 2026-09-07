package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewDto(
    val id: String,
    val author: GroupActorDto,
    @SerialName("media_id") val mediaId: String,
    val body: String,
    @SerialName("contains_spoilers") val containsSpoilers: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateReviewRequestDto(
    @SerialName("media_id") val mediaId: String,
    val body: String,
    @SerialName("contains_spoilers") val containsSpoilers: Boolean,
)

// PATCH /v1/reviews/{id} has no request DTO: the backend rejects an EXPLICIT null for either field
// (UpdateReviewRequest._reject_explicit_nulls), so "leave this field alone" has to be encoded as the
// field's ABSENCE from the JSON body, which a nullable Kotlin property cannot express — the same
// tri-state problem ShowTrackApi.updateLibraryEntry's JsonObject body solves for LibraryPatch.score.
// GroupRepositoryImpl builds the PATCH body by hand for the identical reason.
