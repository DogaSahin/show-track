package com.anarky.showtrack.core.model

import java.time.Instant

data class Media(
    val id: String,
    val source: MediaSource,
    val externalId: String,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val coverImageUrl: String?,
    val status: MediaStatus,
    val nextEpisodeSeason: Int?,
    val nextEpisodeNumber: Int?,
    val nextEpisodeDate: Instant?,
    val daysUntilNextEpisode: Int?,
    /** Regular episodes the server knows of; null until the title's episode list is fetched. */
    val totalEpisodes: Int? = null,
)
