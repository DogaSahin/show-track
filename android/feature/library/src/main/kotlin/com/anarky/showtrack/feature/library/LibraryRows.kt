package com.anarky.showtrack.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.countdownLabel
import com.anarky.showtrack.core.designsystem.component.nextEpisodeLabel
import com.anarky.showtrack.core.model.LibraryEntry

private val CoverWidth = 48.dp
private val AiringCardWidth = 196.dp

/**
 * "Airing soon": your Watching shows with an episode coming, soonest first. A shortcut, so these
 * shows also appear in the grid below. Not drawn at all when there is nothing to show.
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
