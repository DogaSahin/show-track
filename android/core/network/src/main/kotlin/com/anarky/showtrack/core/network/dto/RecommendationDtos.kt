package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shape of a persisted title as it appears inside a recommendation. Deliberately NOT
 * [MediaDto]: `{id, source, external_id, type, title, year, genres, cover_image_url}` — no
 * `status`, no next-episode block. A recommendation is by construction not in the user's library,
 * so the sync job that keeps those fields current never touches it; see
 * `com.anarky.showtrack.core.data.mapper.toDomain` (the `PersistedMediaDto` overload) for where
 * that becomes the mapper's own documented default rather than an accident.
 *
 * It DOES have an `id`, unlike [MediaSummaryDto] — the id is what lets a recommendation row open
 * the detail screen directly, no add-first workaround.
 */
@Serializable
data class PersistedMediaDto(
    val id: String,
    val source: String,
    @SerialName("external_id") val externalId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    @SerialName("cover_image_url") val coverImageUrl: String?,
)

/**
 * One seed — the strongest single contributor, deliberately not all of them (backend decision):
 * "because you liked these four things a little" is not something anyone can act on.
 */
@Serializable
data class RecommendationReasonDto(
    @SerialName("seed_media_id") val seedMediaId: String,
    @SerialName("seed_title") val seedTitle: String,
    @SerialName("matched_genres") val matchedGenres: List<String>,
)

/**
 * Deliberately carries NO score field (backend decision 7-K): publishing the blended float would
 * let a client render a number whose scale was never defined. The ORDERING of [RecommendationPageDto.items]
 * is the score — nothing downstream may invent a percentage, a star rating or a "match" figure.
 */
@Serializable
data class RecommendationDto(
    val media: PersistedMediaDto,
    val reason: RecommendationReasonDto,
)

/** The cursor-paginated list envelope: `{items, next_cursor}` (architecture rule 4). */
@Serializable
data class RecommendationPageDto(
    val items: List<RecommendationDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)
