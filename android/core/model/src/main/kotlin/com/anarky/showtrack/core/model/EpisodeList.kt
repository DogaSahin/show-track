package com.anarky.showtrack.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * A title's seasons and episodes. [syncedAt] null means the server has not fetched the list yet,
 * which the screen shows as "not available yet" rather than as a show with no episodes.
 */
data class EpisodeList(
    val syncedAt: Instant?,
    val totalEpisodes: Int?,
    val seasons: List<Season>,
) {
    val isAvailable: Boolean get() = syncedAt != null
}

data class Season(
    val number: Int,
    val episodes: List<Episode>,
)

/** [aired] is the server's answer for today, so the client never guesses from [airDate]. */
data class Episode(
    val id: String,
    val number: Int,
    val title: String?,
    val airDate: LocalDate?,
    val aired: Boolean,
)
