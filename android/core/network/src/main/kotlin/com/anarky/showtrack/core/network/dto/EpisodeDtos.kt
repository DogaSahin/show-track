package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /v1/media/{id}/episodes`. */
@Serializable
data class EpisodeListDto(
    @SerialName("synced_at") val syncedAt: String?,
    @SerialName("total_episodes") val totalEpisodes: Int?,
    val seasons: List<SeasonDto>,
)

@Serializable
data class SeasonDto(
    val number: Int,
    @SerialName("episode_count") val episodeCount: Int,
    val episodes: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    val id: String,
    val number: Int,
    val title: String?,
    @SerialName("air_date") val airDate: String?,
    val aired: Boolean,
)
