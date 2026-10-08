package com.anarky.showtrack.feature.profile

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.anarky.showtrack.core.designsystem.component.SkeletonBlock
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.GenreCount
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

private val BarHeight = 12.dp
private val SegmentGap = 2.dp
private val LegendDotSize = 8.dp
private val GenreBarHeight = 8.dp
private val GenreNameWidth = 62.dp
private const val TABULAR = "tnum"

/**
 * The stats block: headline count, status bar and legend, the four tiles, then top genres.
 *
 * Every state keeps the settings below it usable: a failed load is a note with Retry in place of
 * the numbers, never a full-screen error, because nothing about signing out or importing depends on
 * the stats having loaded (decision C-S).
 */
@Composable
internal fun StatsBlock(
    state: LibraryStatsUiState,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
) {
    when (state) {
        is LibraryStatsUiState.Loading -> StatsSkeleton()
        is LibraryStatsUiState.Error ->
            StatsNote(
                message = stringResource(R.string.profile_stats_error),
                actionLabel = stringResource(DesignSystemR.string.action_retry),
                onAction = onRetry,
            )
        is LibraryStatsUiState.Success ->
            Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
                if (state.isStale) StaleDataBanner(onRetry = onRetry)
                if (state.stats.total == 0) {
                    StatsNote(
                        message = stringResource(R.string.profile_stats_empty),
                        actionLabel = stringResource(R.string.profile_stats_empty_action),
                        onAction = onSearch,
                    )
                } else {
                    StatsContent(stats = state.stats)
                }
            }
    }
}

@Composable
private fun StatsContent(stats: LibraryStats) {
    Column(verticalArrangement = Arrangement.spacedBy(space = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(space = 10.dp)) {
            SectionLabel(
                text =
                    stringResource(R.string.profile_stats_figure, stats.total) + " " +
                        pluralStringResource(R.plurals.profile_stats_total_label, stats.total),
            )
            StatusDistribution(byStatus = stats.byStatus)
        }
        StatTiles(stats = stats)
        if (stats.topGenres.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(space = 10.dp)) {
                SectionLabel(text = stringResource(R.string.profile_stats_genres_title))
                GenreRanking(genres = stats.topGenres)
            }
        }
    }
}

/** "142 TITLES", "TOP GENRES", "APP": the small uppercase labels over each part of the screen. */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 0.04.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The bar split by status, in the status colours, and its legend. Statuses with no titles are left
 * out of both. The bar is ONE accessibility node that reads as a list ("Watching 6, Planned 38, …");
 * a row of coloured boxes means nothing to a screen reader.
 */
@Composable
private fun StatusDistribution(byStatus: Map<UserMediaStatus, Int>) {
    val present = UserMediaStatus.entries.filter { (byStatus[it] ?: 0) > 0 }
    if (present.isEmpty()) return
    val spoken = present.map { "${it.label()} ${byStatus.getValue(it)}" }.joinToString(separator = ", ")

    Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(space = SegmentGap),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(BarHeight)
                    .clip(CircleShape)
                    .clearAndSetSemantics { contentDescription = spoken },
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
        Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            present.chunked(size = 2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(space = 16.dp)) {
                    pair.forEach { status ->
                        LegendEntry(
                            status = status,
                            count = byStatus.getValue(status),
                            modifier = Modifier.weight(weight = 1f),
                        )
                    }
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
        Box(modifier = Modifier.size(LegendDotSize).clip(CircleShape).background(status.markColor()))
        Text(
            text = status.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(weight = 1f).padding(start = 8.dp),
        )
        Text(
            text = stringResource(R.string.profile_stats_figure, count),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatTiles(stats: LibraryStats) {
    val average = stats.averageScore
    Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            StatTile(
                value = stringResource(R.string.profile_stats_figure, stats.episodesWatched),
                label = stringResource(R.string.profile_stats_episodes_label),
                modifier = Modifier.weight(weight = 1f),
            )
            StatTile(
                value = average?.toPlainString() ?: stringResource(R.string.profile_stats_no_ratings_figure),
                showStar = average != null,
                label =
                    if (average != null) {
                        pluralStringResource(R.plurals.profile_stats_average_label, stats.ratedCount, stats.ratedCount)
                    } else {
                        stringResource(R.string.profile_stats_no_ratings)
                    },
                modifier = Modifier.weight(weight = 1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            StatTile(
                value = stringResource(R.string.profile_stats_figure, stats.favorites),
                label = stringResource(R.string.profile_stats_favorites_label),
                modifier = Modifier.weight(weight = 1f),
            )
            StatTile(
                value = stringResource(R.string.profile_stats_figure, stats.addedThisMonth),
                label = stringResource(R.string.profile_stats_added_label),
                modifier = Modifier.weight(weight = 1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    showStar: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text =
                    buildAnnotatedString {
                        append(value)
                        if (showStar) withStyle(SpanStyle(color = StarGold)) { append(" ★") }
                    },
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = TABULAR),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Up to five genres, each bar's length relative to the top one. */
@Composable
private fun GenreRanking(genres: List<GenreCount>) {
    val leader = genres.first().count.coerceAtLeast(minimumValue = 1)
    Column(verticalArrangement = Arrangement.spacedBy(space = 10.dp)) {
        genres.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space = 10.dp),
            ) {
                Text(
                    text = entry.genre,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(GenreNameWidth),
                )
                Box(modifier = Modifier.weight(weight = 1f).height(GenreBarHeight)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(fraction = entry.count.toFloat() / leader)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = stringResource(R.string.profile_stats_figure, entry.count),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A short message with one action, in place of the numbers: the empty library and the failed load. */
@Composable
private fun StatsNote(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(weight = 1f),
            )
            TextButton(onClick = onAction) { Text(text = actionLabel) }
        }
    }
}

/** A grey stand-in for the bar and the four tiles while the stats load. */
@Composable
private fun StatsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        SkeletonBlock(modifier = Modifier.width(96.dp).height(10.dp))
        SkeletonBlock(shape = CircleShape, modifier = Modifier.fillMaxWidth().height(BarHeight))
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                repeat(2) {
                    SkeletonBlock(
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.weight(weight = 1f).height(64.dp),
                    )
                }
            }
        }
    }
}
