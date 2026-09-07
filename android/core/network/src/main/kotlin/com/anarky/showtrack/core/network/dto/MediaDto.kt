package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shape of `MediaDetail`: a persisted title plus its airing state.
 *
 * Every field is the wire type, not the domain type — `source`/`type`/`status` stay `String`
 * and the timestamps stay ISO-8601 `String`. Parsing them into the enums and `Instant` of
 * `:core:model` is :core:data's job, so an unrecognised enum value from a newer backend is a
 * mapping decision there rather than a deserialization crash here.
 */
@Serializable
data class MediaDto(
    val id: String,
    val source: String,
    @SerialName("external_id") val externalId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    @SerialName("cover_image_url") val coverImageUrl: String?,
    val status: String,
    @SerialName("next_episode_season") val nextEpisodeSeason: Int?,
    @SerialName("next_episode_number") val nextEpisodeNumber: Int?,
    @SerialName("next_episode_date") val nextEpisodeDate: String?,
    @SerialName("days_until_next_episode") val daysUntilNextEpisode: Int?,
)
