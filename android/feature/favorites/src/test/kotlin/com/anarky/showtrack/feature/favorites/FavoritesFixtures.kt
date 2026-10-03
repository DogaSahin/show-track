package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import java.math.BigDecimal
import java.time.Instant

/** A favourite with a stable id, its media id derived from it, and an optional score. */
internal fun favourite(
    id: String,
    title: String = id,
    type: MediaType = MediaType.ANIME,
    score: String? = null,
) = LibraryEntry(
    id = id,
    status = UserMediaStatus.COMPLETED,
    score = score?.let(::BigDecimal),
    progress = 0,
    favorite = true,
    updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
    media =
        Media(
            id = "media-$id",
            source = MediaSource.ANILIST,
            externalId = id,
            type = type,
            title = title,
            year = 2023,
            genres = emptyList(),
            coverImageUrl = null,
            status = MediaStatus.FINISHED,
            nextEpisodeSeason = null,
            nextEpisodeNumber = null,
            nextEpisodeDate = null,
            daysUntilNextEpisode = null,
        ),
)
