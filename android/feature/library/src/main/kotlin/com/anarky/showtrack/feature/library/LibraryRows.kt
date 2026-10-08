package com.anarky.showtrack.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.countdownLabel
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.designsystem.component.nextEpisodeLabel
import com.anarky.showtrack.core.designsystem.component.typeAndYear
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.LibraryEntry

private val CoverWidth = 48.dp
private val AiringCardWidth = 196.dp
private val DotSize = 8.dp

/**
 * One show in the list: cover, title, "Anime · 2023 ★ 8.5", then the status and where you are.
 *
 * The last line, after the status: the next episode and when it airs if one is coming
 * ("Ep 15 in 2 days", the countdown in `primary`); otherwise how far you are ("14 eps watched",
 * "Not started"). Totals ("14 of 28") join in once shows carry an episode count.
 */
@Composable
internal fun LibraryRow(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = entry.media
    Row(
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(CoverWidth))
        Column(verticalArrangement = Arrangement.spacedBy(space = 3.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    buildAnnotatedString {
                        append(media.typeAndYear())
                        entry.score?.let { score ->
                            append("  ")
                            withStyle(SpanStyle(color = StarGold)) { append("★ ") }
                            withStyle(
                                SpanStyle(color = MaterialTheme.colorScheme.onSurface),
                            ) { append(score.toPlainString()) }
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            StatusLine(entry = entry)
        }
    }
}

@Composable
private fun StatusLine(entry: LibraryEntry) {
    val statusColor = entry.status.markColor()
    val episode = entry.media.nextEpisodeLabel()
    val days = entry.media.daysUntilNextEpisode
    val primary = MaterialTheme.colorScheme.primary
    val total = entry.media.totalEpisodes?.takeIf { it > 0 }
    val progress =
        when {
            episode != null && days != null -> null
            entry.progress > 0 && total != null ->
                stringResource(
                    R.string.library_eps_watched_of,
                    entry.progress,
                    total,
                )
            entry.progress > 0 -> pluralStringResource(R.plurals.library_eps_watched, entry.progress, entry.progress)
            total != null -> pluralStringResource(R.plurals.library_not_started_with_total, total, total)
            else -> stringResource(R.string.library_not_started)
        }
    // "14/28 · " ahead of an upcoming episode, when the count is known.
    val countBeforeUpcoming = total?.let { stringResource(R.string.library_eps_count_short, entry.progress, it) }
    val upcoming =
        if (episode != null && days != null) {
            stringResource(R.string.library_episode_when, episode, countdownLabel(days))
        } else {
            null
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space = 6.dp)) {
        Box(modifier = Modifier.size(DotSize).clip(CircleShape).background(statusColor))
        Text(
            text =
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(color = statusColor, fontWeight = FontWeight.Medium),
                    ) { append(entry.status.label()) }
                    append(" · ")
                    if (upcoming != null) {
                        countBeforeUpcoming?.let {
                            append(it)
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = primary)) { append(upcoming) }
                    } else {
                        append(progress)
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "Airing soon": your Watching shows with an episode coming, soonest first. A shortcut, so these
 * shows also appear in the list below. Not drawn at all when there is nothing to show.
 */
@Composable
internal fun AiringSoonSection(
    entries: List<LibraryEntry>,
    onEntryClick: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(space = 8.dp), modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.library_airing_soon),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(space = 10.dp),
        ) {
            items(items = entries, key = LibraryEntry::id) { entry ->
                AiringSoonCard(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
    }
}

@Composable
private fun AiringSoonCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
) {
    val media = entry.media
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(AiringCardWidth),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(space = 10.dp), modifier = Modifier.padding(all = 8.dp)) {
            MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(CoverWidth))
            Column(verticalArrangement = Arrangement.spacedBy(space = 2.dp)) {
                media.daysUntilNextEpisode?.let { days ->
                    Text(
                        text = countdownLabel(days),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                media.nextEpisodeLabel()?.let { episode ->
                    Text(
                        text = episode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
