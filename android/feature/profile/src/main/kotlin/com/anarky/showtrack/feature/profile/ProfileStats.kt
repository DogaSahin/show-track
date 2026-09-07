package com.anarky.showtrack.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.GenreCount
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus

private val BarHeight = 10.dp
private val LegendDotSize = 8.dp
private val GenreBarHeight = 6.dp

/**
 * The library-stats block.
 *
 * The five status counts were five lines of `"Watching: 12"` until this task, which is an accurate
 * rendering of a distribution that nobody can see: reading proportion off five numbers is work the
 * screen can do for the reader. The bar is that work — one row, segment widths straight off the
 * counts — and the legend underneath is what keeps the exact numbers available, since a bar alone
 * answers "mostly completed" but not "how many".
 *
 * Everything here is derived from what `GET /v1/library/stats` already sends. There is deliberately
 * no hours-watched figure: no episode runtime exists anywhere in the schema (see the backend's own
 * `LibraryStats` docstring), and a figure derived from a per-type constant would sit in the same
 * typeface as the four measured ones.
 */
@Composable
internal fun StatsContent(stats: LibraryStats) {
    Column(verticalArrangement = Arrangement.spacedBy(space = 20.dp)) {
        StatsHeadline(total = stats.total)
        if (stats.total > 0) {
            StatusDistribution(byStatus = stats.byStatus)
        }
        StatsFigures(stats = stats)
        if (stats.topGenres.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GenreRanking(genres = stats.topGenres)
        }
    }
}

/**
 * The total, as a figure rather than a sentence. `displaySmall` because this is the one number on
 * the screen that answers "how big is my library" — everything below it is a breakdown of this.
 */
@Composable
private fun StatsHeadline(total: Int) {
    Column {
        Text(
            text = stringResource(R.string.profile_stats_figure, total),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = pluralStringResource(R.plurals.profile_stats_total_label, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A proportional bar plus its legend.
 *
 * Segment widths come from `Modifier.weight(count)` rather than a measured fraction: `Row` already
 * divides its space in proportion to the weights, so the counts can be handed over raw and there is
 * no total to keep in sync. Every rendered status has a count of at least 1 (absent statuses are
 * absent from the map, never zero — [LibraryStats]'s own KDoc), which matters because a zero weight
 * is not allowed.
 *
 * Iterating [UserMediaStatus.entries] and skipping what the map lacks, rather than iterating the
 * map, is what gives both the bar and the legend a STABLE order — a map's iteration order would let
 * the segments rearrange between two refreshes of the same library.
 *
 * The colours are `:core:designsystem`'s [markColor], not local ones, so a WATCHING segment here is
 * the same violet as a WATCHING dot on a library row.
 */
@Composable
private fun StatusDistribution(byStatus: Map<UserMediaStatus, Int>) {
    val present = UserMediaStatus.entries.filter { byStatus.containsKey(it) }
    if (present.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(BarHeight)
                    .clip(CircleShape),
        ) {
            present.forEach { status ->
                Box(
                    modifier =
                        Modifier
                            .weight(weight = byStatus.getValue(status).toFloat())
                            .fillMaxHeight()
                            .background(status.markColor()),
                )
            }
        }
        // Two per row rather than a FlowRow: five entries at a large font scale is exactly where a
        // flow layout starts wrapping unpredictably, and a fixed pair-per-row is legible at every
        // width this screen is ever laid out at.
        Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            present.chunked(size = 2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(space = 12.dp)) {
                    pair.forEach { status ->
                        LegendEntry(
                            status = status,
                            count = byStatus.getValue(status),
                            modifier = Modifier.weight(weight = 1f),
                        )
                    }
                    // Keeps the last odd entry at half width instead of letting it stretch across
                    // the row and break the column the four above it form.
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(weight = 1f))
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(
    status: UserMediaStatus,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(LegendDotSize)
                    .clip(CircleShape)
                    .background(status.markColor()),
        )
        Text(
            text = status.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(weight = 1f, fill = false).padding(start = 8.dp),
        )
        Text(
            text = stringResource(R.string.profile_stats_figure, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Four figures in a 2x2 grid, built from two [Row]s rather than a `LazyVerticalGrid`: this whole
 * screen already sits inside a `verticalScroll`, and a lazy grid nested in an infinite-height
 * parent throws at measure time.
 *
 * The average score is the only one that can be absent, and it renders as "No ratings yet" rather
 * than 0.0 — the same distinction the old text version drew, and for the same reason: a displayed
 * 0.0 claims every title was rated zero.
 */
@Composable
private fun StatsFigures(stats: LibraryStats) {
    val average = stats.averageScore
    Column(verticalArrangement = Arrangement.spacedBy(space = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(space = 16.dp)) {
            StatFigure(
                value = stringResource(R.string.profile_stats_figure, stats.episodesWatched),
                label = stringResource(R.string.profile_stats_episodes_label),
                modifier = Modifier.weight(weight = 1f),
            )
            StatFigure(
                value = average?.toPlainString() ?: stringResource(R.string.profile_stats_no_ratings_figure),
                label =
                    if (average != null) {
                        pluralStringResource(R.plurals.profile_stats_average_label, stats.ratedCount, stats.ratedCount)
                    } else {
                        stringResource(R.string.profile_stats_no_ratings)
                    },
                modifier = Modifier.weight(weight = 1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(space = 16.dp)) {
            StatFigure(
                value = stringResource(R.string.profile_stats_figure, stats.addedThisMonth),
                label = stringResource(R.string.profile_stats_added_label),
                modifier = Modifier.weight(weight = 1f),
            )
            StatFigure(
                value = stringResource(R.string.profile_stats_figure, stats.favorites),
                label = stringResource(R.string.profile_stats_favorites_label),
                modifier = Modifier.weight(weight = 1f),
            )
        }
    }
}

@Composable
private fun StatFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Two lines reserved whether or not this caption needs them: the average's caption names
        // the measure AND its denominator and wraps, and without a floor the tile beside it sits
        // shorter, breaking the grid the four figures are supposed to form.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
        )
    }
}

/**
 * The genre ranking, as bars scaled against the TOP genre rather than against the library total.
 *
 * Scaling to the leader is the deliberate choice: genres overlap (one title carries several), so
 * the counts do not sum to anything meaningful and a bar drawn as a fraction of the total would
 * make every genre look negligible in a large library. Against the leader, the bars answer the
 * question the ranking actually poses — how far ahead is first place.
 *
 * The server has already ranked, capped and tie-broken this list, so nothing here re-sorts it; the
 * first element is the leader by construction.
 */
@Composable
private fun GenreRanking(genres: List<GenreCount>) {
    val leader = genres.first().count.coerceAtLeast(minimumValue = 1)
    Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        Text(
            text = stringResource(R.string.profile_stats_genres_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        genres.forEach { entry ->
            GenreRow(entry = entry, leader = leader)
        }
    }
}

@Composable
private fun GenreRow(
    entry: GenreCount,
    leader: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(space = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.genre,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight = 1f),
            )
            Text(
                text = stringResource(R.string.profile_stats_figure, entry.count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The track is drawn first and the fill laid over it at a fraction of the width, rather
        // than two weighted siblings: a weighted pair would need a second, complementary weight
        // that goes to zero for the leader, and a zero weight is not allowed.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(GenreBarHeight)
                    .clip(RoundedCornerShape(size = GenreBarHeight))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction = entry.count.toFloat() / leader)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(size = GenreBarHeight))
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
