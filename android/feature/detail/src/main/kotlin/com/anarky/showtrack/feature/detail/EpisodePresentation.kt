package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.Episode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DashLength = 3.dp

/** A dashed outline: a ring, or a rounded square when [cornerRadius] is given (an anime tile). */
@Composable
internal fun DashedOutline(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
) {
    Canvas(modifier = modifier) {
        val dash = DashLength.toPx()
        val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
        val inset = 1.dp.toPx()
        if (cornerRadius == null) {
            drawCircle(color = color, radius = this.size.minDimension / 2 - inset, style = stroke)
        } else {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = stroke,
            )
        }
    }
}

/**
 * "Episode 7, Good News About Hell, aired 21 Feb, watched": the number always, the title when
 * there is one, the watched part only when tracked.
 */
@Composable
internal fun episodeDescription(
    episode: Episode,
    watched: Boolean,
    tracking: Boolean,
    today: LocalDate,
): String {
    val number = stringResource(R.string.detail_episode_fallback_title, episode.number)
    val label = episode.title?.let { stringResource(R.string.detail_episode_label_titled, number, it) } ?: number
    val date = episode.airDate
    val airing =
        when {
            date == null -> stringResource(R.string.detail_episode_date_unknown)
            episode.aired -> stringResource(R.string.detail_episode_aired_on, formatAirDate(date, today))
            else -> stringResource(R.string.detail_episode_airs_on, formatAirDate(date, today))
        }
    if (!tracking) return stringResource(R.string.detail_episode_a11y_untracked, label, airing)
    val mark = stringResource(if (watched) R.string.detail_episode_watched else R.string.detail_episode_not_watched)
    return stringResource(R.string.detail_episode_a11y, label, airing, mark)
}

/** "21 Feb", with the year when it isn't this year's. */
internal fun formatAirDate(
    date: LocalDate,
    today: LocalDate,
): String {
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
