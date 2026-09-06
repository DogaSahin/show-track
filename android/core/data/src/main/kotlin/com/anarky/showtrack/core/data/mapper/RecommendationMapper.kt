package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
import com.anarky.showtrack.core.network.dto.PersistedMediaDto
import com.anarky.showtrack.core.network.dto.RecommendationDto
import com.anarky.showtrack.core.network.dto.RecommendationReasonDto

/**
 * `PersistedMediaDto` carries no `status` and no next-episode block at all — unlike [MediaDto]'s
 * own `toDomain()`, there is nothing here to parse for those fields. [Media.status] is set to
 * [MediaStatus.NOT_YET_AIRED] and every next-episode field to `null` as this mapper's OWN
 * deliberate default, standing in for "unknown", not for "confirmed not airing" — a recommendation
 * is by construction not in the user's library, so the airing sync job that would keep those
 * fields current never runs against it. `:feature:discover`'s screen must never render a countdown
 * or an airing-status badge off a [Media] produced by this function; see [Recommendation]'s own
 * KDoc for the same note from the domain side.
 */
fun PersistedMediaDto.toDomain(): Media =
    Media(
        id = id,
        source = MediaSource.valueOf(source.uppercase()),
        externalId = externalId,
        type = MediaType.valueOf(type.uppercase()),
        title = title,
        year = year,
        genres = genres,
        coverImageUrl = coverImageUrl,
        status = MediaStatus.NOT_YET_AIRED,
        nextEpisodeSeason = null,
        nextEpisodeNumber = null,
        nextEpisodeDate = null,
        daysUntilNextEpisode = null,
    )

fun RecommendationReasonDto.toDomain(): RecommendationReason =
    RecommendationReason(seedMediaId = seedMediaId, seedTitle = seedTitle, matchedGenres = matchedGenres)

fun RecommendationDto.toDomain(): Recommendation = Recommendation(media = media.toDomain(), reason = reason.toDomain())
