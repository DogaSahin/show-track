package com.anarky.showtrack.core.model

/**
 * One row of the recommendations feed (`GET /v1/recommendations`).
 *
 * [media] is a full [Media] — reused rather than a new domain type, but its `status` and
 * next-episode fields are UNPOPULATED BY CONSTRUCTION: a recommendation is, by definition, not in
 * the user's library, so the airing sync never refreshes those fields for it. See
 * `com.anarky.showtrack.core.data.mapper`'s `PersistedMediaDto.toDomain()` for the mapper's
 * documented defaults, and never render a countdown or an airing-status badge off a [Media] that
 * arrived through this type.
 *
 * There is deliberately no score field anywhere on this type (backend decision 7-K): the position
 * of a [Recommendation] within the feed's list IS the ranking. Nothing downstream may invent a
 * percentage, a star rating or a "match" figure to stand in for it.
 */
data class Recommendation(
    val media: Media,
    val reason: RecommendationReason,
)

/**
 * One seed — the single strongest contributor to this recommendation, deliberately not every title
 * that influenced it: "because you liked these four things a little" is not something a user can
 * act on the way "because you watched Frieren" is.
 */
data class RecommendationReason(
    val seedMediaId: String,
    val seedTitle: String,
    val matchedGenres: List<String>,
)
