package com.anarky.showtrack.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.typeAndYear
import com.anarky.showtrack.core.designsystem.theme.Ink03
import com.anarky.showtrack.core.designsystem.theme.Paper100
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.LibraryEntry

private const val BADGE_ALPHA = 0.72f

/**
 * A favourite's poster: cover with its score badge, title, and optionally "Anime · 2023". Tap opens
 * show details; long-press opens Open / Remove from favorites. The long-press carries its own
 * label, so TalkBack offers "Remove from favorites" as an action rather than a silent gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FavoritePoster(
    entry: LibraryEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    showTypeAndYear: Boolean = false,
) {
    FavoriteMenuHost(onOpen = onOpen, onRemove = onRemove, modifier = modifier) { openMenu ->
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 5.dp),
            modifier =
                Modifier.combinedClickable(
                    onClick = onOpen,
                    onLongClick = openMenu,
                    onLongClickLabel = stringResource(R.string.favorites_remove_action),
                ),
        ) {
            Box {
                MediaCover(coverImageUrl = entry.media.coverImageUrl, modifier = Modifier.fillMaxWidth())
                entry.score?.let { score ->
                    val spoken = stringResource(R.string.favorites_score_description, score.toPlainString())
                    ScoreBadge(
                        score = score.toPlainString(),
                        modifier = Modifier.padding(all = 5.dp).clearAndSetSemantics { contentDescription = spoken },
                    )
                }
            }
            Text(
                text = entry.media.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showTypeAndYear) {
                Text(
                    text = entry.media.typeAndYear(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Wraps a poster with the long-press menu. The content gets the function that opens it, so the
 * podium's own layout can reuse the same menu.
 */
@Composable
internal fun FavoriteMenuHost(
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        content { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.favorites_open_action)) },
                onClick = {
                    menuOpen = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.favorites_remove_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/** "★ 9.5" on a dark chip in the poster's corner, readable over any cover. */
@Composable
private fun ScoreBadge(
    score: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = StarGold)) { append("★ ") }
                append(score)
            },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Paper100,
        modifier =
            modifier
                .background(Ink03.copy(alpha = BADGE_ALPHA), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
