package com.anarky.showtrack.feature.profile.alerts

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.UserMediaStatus
import java.time.Duration
import java.time.Instant

private const val DAY_BEFORE_HOURS = 24L
private const val SOON_BEFORE_HOURS = 6L

/** How long before an episode airs an alert is meant to show. */
enum class AlertLead(
    val before: Duration,
    val key: String,
) {
    DAY(Duration.ofHours(DAY_BEFORE_HOURS), "24h"),
    SOON(Duration.ofHours(SOON_BEFORE_HOURS), "6h"),
}

/** One alert to schedule: what it is about, and how long from now it should show. */
data class PlannedAlert(
    val name: String,
    val mediaId: String,
    val title: String,
    val season: Int?,
    val episode: Int,
    val airsAt: Instant,
    val lead: AlertLead,
    val delay: Duration,
)

/**
 * Which alerts a library should have right now. Pure, so the maths is tested without WorkManager.
 *
 * - Only Watching titles with a known next episode that has not aired yet.
 * - Each lead (24 h, 6 h) still in the future gets an alert.
 * - First seen less than 6 h before airing: exactly one alert, shown now. It keeps the 6 h alert's
 *   name, so a later re-plan or an earlier 6 h alert that already fired never makes a second.
 */
object AlertRules {
    fun plan(
        entries: List<LibraryEntry>,
        now: Instant,
    ): List<PlannedAlert> = entries.filter { it.status == UserMediaStatus.WATCHING }.flatMap { plan(it, now) }

    fun name(
        mediaId: String,
        season: Int?,
        episode: Int,
        lead: AlertLead,
    ): String = "alert-$mediaId-${season ?: 0}-$episode-${lead.key}"

    // Guard clauses: each early return is one reason a title gets no alert.
    @Suppress("ReturnCount")
    private fun plan(
        entry: LibraryEntry,
        now: Instant,
    ): List<PlannedAlert> {
        val media = entry.media
        val airsAt = media.nextEpisodeDate ?: return emptyList()
        val episode = media.nextEpisodeNumber ?: return emptyList()
        if (!airsAt.isAfter(now)) return emptyList()
        val untilAir = Duration.between(now, airsAt)

        fun alert(
            lead: AlertLead,
            delay: Duration,
        ) = PlannedAlert(
            name = name(media.id, media.nextEpisodeSeason, episode, lead),
            mediaId = media.id,
            title = media.title,
            season = media.nextEpisodeSeason,
            episode = episode,
            airsAt = airsAt,
            lead = lead,
            delay = delay,
        )

        if (untilAir <= AlertLead.SOON.before) return listOf(alert(AlertLead.SOON, Duration.ZERO))
        return AlertLead.entries.mapNotNull { lead ->
            val delay = untilAir - lead.before
            if (delay.isNegative) null else alert(lead, delay)
        }
    }
}
