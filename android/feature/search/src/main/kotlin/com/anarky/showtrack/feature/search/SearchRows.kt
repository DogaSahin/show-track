package com.anarky.showtrack.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.SkeletonBlock
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.UserMediaStatus

private val CoverWidth = 44.dp
private val CoverHeight = 66.dp
private val PillHeight = 32.dp
private val AddingSpinnerSize = 16.dp
private val StatusDotSize = 7.dp
private val RecentRowHeight = 44.dp
private const val MAX_GENRES = 2
private const val SKELETON_ROWS = 6
private const val SKELETON_TITLE_FRACTION = 0.7f
private const val SKELETON_LINE_FRACTION = 0.45f

/** "Anime · 2024 · Romance · Sports": type, year, at most two genres. */
@Composable
private fun MediaSummary.subtitle(separator: String): String =
    (listOfNotNull(type.label(), year?.toString()) + genres.take(MAX_GENRES)).joinToString(separator)

/**
 * One result. The whole row opens the title; + Add is its own button, and a title already in the
 * library shows its status instead (not a button). TalkBack reads the row as one sentence, then
 * the Add button separately.
 */
@Suppress("LongParameterList")
@Composable
internal fun SearchResultRow(
    result: SearchResult,
    adding: Boolean,
    opening: Boolean,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = result.media
    val spoken = listOf(media.title, media.subtitle(separator = ", ")).joinToString(", ")
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (opening) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaCover(
            coverImageUrl = media.coverImageUrl,
            modifier = Modifier.width(CoverWidth).height(CoverHeight).clip(MaterialTheme.shapes.extraSmall),
        )
        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = media.subtitle(separator = " · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val status = result.libraryStatus
        when {
            adding ->
                Box(modifier = Modifier.size(PillHeight), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(AddingSpinnerSize), strokeWidth = 2.dp)
                }
            status != null -> StatusChip(status = status)
            else -> AddButton(title = media.title, onClick = onAdd)
        }
    }
}

@Composable
private fun AddButton(
    title: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.search_add_content_description, title)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier =
            Modifier
                .height(PillHeight)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CircleShape)
                // Its own focusable node (the clickable Surface keeps it out of the row's merge),
                // read once, as a button: "Add Blue Lock to your library, button".
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
    ) {
        Row(
            // The visible "Add" would otherwise be read again after the description.
            modifier = Modifier.padding(horizontal = 12.dp).clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AddingSpinnerSize),
            )
            Text(
                text = stringResource(R.string.search_add_button),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusChip(status: UserMediaStatus) {
    val label = status.label()
    val spoken = stringResource(R.string.search_in_library, label)
    Row(
        modifier =
            Modifier
                .height(PillHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.size(StatusDotSize).clip(CircleShape).background(status.markColor()))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Grey blocks in the shape of result rows while the first page of a query loads. */
@Composable
internal fun SearchSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics { }) {
        repeat(SKELETON_ROWS) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SkeletonBlock(
                    modifier = Modifier.width(CoverWidth).height(CoverHeight),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION).height(14.dp))
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_LINE_FRACTION).height(11.dp))
                }
            }
        }
    }
}

/** Before typing: the phone's recent searches, or nothing at all when there are none yet. */
@Composable
internal fun RecentSearchList(
    recent: List<String>,
    onRun: (String) -> Unit,
    onFill: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recent.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_recent_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(text = stringResource(R.string.search_recent_clear)) }
        }
        recent.forEach { query ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RecentRowHeight)
                        .clickable { onRun(query) }
                        .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onFill(query) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_north_west),
                        contentDescription = stringResource(R.string.search_recent_fill_content_description, query),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
