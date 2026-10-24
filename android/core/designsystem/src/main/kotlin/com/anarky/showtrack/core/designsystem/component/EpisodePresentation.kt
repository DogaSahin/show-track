package com.anarky.showtrack.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.Media

/**
 * "Today", "Tomorrow", "in 3 days": when the next episode airs, as a headline or the end of a line.
 * Not `countdown_days_remaining` ("3 days"), which [CountdownBadge] keeps: on its own, "3 days"
 * does not say whether that is until or since.
 */
@Composable
fun countdownLabel(daysUntil: Int): String =
    when {
        daysUntil <= 0 -> stringResource(R.string.countdown_today)
        daysUntil == 1 -> stringResource(R.string.countdown_tomorrow)
        else -> pluralStringResource(R.plurals.countdown_in_days, daysUntil, daysUntil)
    }

/**
 * "S2 E7" when the title numbers its seasons, "Ep 15" when it does not (most anime). Null when the
 * next episode is unknown.
 */
@Composable
fun Media.nextEpisodeLabel(): String? {
    val episode = nextEpisodeNumber ?: return null
    val season = nextEpisodeSeason
    return if (season != null) {
        stringResource(R.string.episode_label_season, season, episode)
    } else {
        stringResource(R.string.episode_label, episode)
    }
}
