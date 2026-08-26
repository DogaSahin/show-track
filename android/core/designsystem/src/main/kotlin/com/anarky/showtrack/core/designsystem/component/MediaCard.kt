package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.UserMediaStatus
import java.math.BigDecimal

/**
 * A row card for one [Media] title: cover, title, year, the user's library status (if any) and
 * score.
 *
 * [status] is deliberately separate from [media] and from `media.status` — the latter is the
 * title's *airing* state (AIRING/FINISHED/NOT_YET_AIRED), while [status] is what *this user* did
 * with it (WATCHING/COMPLETED/…). A search result carries the first and not the second, hence
 * [status] is nullable and no badge is rendered when it is null.
 */
@Composable
fun MediaCard(
    media: Media,
    status: UserMediaStatus?,
    score: BigDecimal?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(all = 12.dp)) {
            MediaCoverPlaceholder(modifier = Modifier.size(width = 64.dp, height = 96.dp))
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(space = 4.dp),
            ) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                media.year?.let { year ->
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                    status?.let { StatusBadge(status = it) }
                    ScoreChip(score = score)
                }
            }
        }
    }
}

// media.coverImageUrl is not read here: no image-loading dependency is wired into this module
// yet (Phase 8 scope is the theme and the component shapes, not networked images), so every card
// renders this placeholder today, including when a cover URL is present. This is exactly what
// lets the gallery preview render offline with previewMedia.coverImageUrl = null, and it is the
// path a future task adds real loading behind — the call site above will not need to change.
@Composable
private fun MediaCoverPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {}
}

@Composable
private fun StatusBadge(
    status: UserMediaStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = status.containerColor(),
    ) {
        Text(
            text = status.label(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
