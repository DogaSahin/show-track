package com.anarky.showtrack.feature.profile.alerts

import android.content.res.Resources
import com.anarky.showtrack.feature.profile.R
import java.time.Duration
import java.time.Instant

/**
 * The alert's line under the title: "S2 E7 airs tomorrow", "E12 airs in 6 hours". Worked out when
 * the alert shows, not when it was planned, so one that shows late (Doze, or first seen 3 hours
 * before airing) still tells the truth.
 */
internal object AlertText {
    private val TOMORROW = Duration.ofHours(18)
    private const val MINUTES_PER_HOUR = 60L

    fun body(
        resources: Resources,
        season: Int?,
        episode: Int,
        airsAt: Instant,
        now: Instant,
    ): String {
        val code =
            if (season != null) {
                resources.getString(R.string.alerts_episode_code_season, season, episode)
            } else {
                resources.getString(R.string.alerts_episode_code, episode)
            }
        val untilAir = Duration.between(now, airsAt)
        // Rounded to the nearest hour: 5 h 40 min reads "in 6 hours".
        val hours = ((untilAir.toMinutes() + MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR).toInt()
        return when {
            untilAir >= TOMORROW -> resources.getString(R.string.alerts_airs_tomorrow, code)
            hours >= 1 -> resources.getQuantityString(R.plurals.alerts_airs_in_hours, hours, code, hours)
            else -> resources.getString(R.string.alerts_airs_soon, code)
        }
    }
}
