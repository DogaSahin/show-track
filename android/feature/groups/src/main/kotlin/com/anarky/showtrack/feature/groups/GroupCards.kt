package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.AvatarStack
import com.anarky.showtrack.core.designsystem.component.GroupAvatar
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.model.Group

private val CardAvatarSize = 40.dp
private val PreviewPosterWidth = 42.dp
private val PreviewPosterHeight = 63.dp
private const val PREVIEW_POSTERS = 4

/**
 * One group in the list: its avatar and name, how many members and whether it is the active group,
 * the first faces, and the first watchlist posters. Everything after the name comes from the list's
 * summary; when a field is not known (an older server), its part is left out rather than shown as 0.
 */
@Composable
internal fun GroupCard(
    group: Group,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(space = 10.dp), modifier = Modifier.padding(all = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
            ) {
                GroupAvatar(groupId = group.id, name = group.name, size = CardAvatarSize)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group.subtitle(isActive)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AvatarStack(people = group.memberPreview)
            }
            WatchlistPreviewRow(group = group)
        }
    }
}

/** "4 members · Active", or whichever half is known. */
@Composable
private fun Group.subtitle(isActive: Boolean): String? {
    val parts =
        listOfNotNull(
            memberCount?.let { pluralStringResource(R.plurals.groups_card_members, it, it) },
            if (isActive) stringResource(R.string.groups_card_active) else null,
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = " · ")
}

@Composable
private fun WatchlistPreviewRow(group: Group) {
    val count = group.watchlistCount ?: return
    if (count == 0) {
        Text(
            text = stringResource(R.string.groups_card_watchlist_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space = 6.dp)) {
        group.watchlistPreview.take(PREVIEW_POSTERS).forEach { cover ->
            MediaCover(coverImageUrl = cover.coverImageUrl, modifier = Modifier.width(PreviewPosterWidth))
        }
        val more = count - group.watchlistPreview.take(PREVIEW_POSTERS).size
        if (more > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(PreviewPosterWidth).height(PreviewPosterHeight),
            ) {
                Text(
                    text = stringResource(R.string.groups_card_more, more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
