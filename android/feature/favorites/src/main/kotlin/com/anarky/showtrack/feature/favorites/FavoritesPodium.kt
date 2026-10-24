package com.anarky.showtrack.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.theme.MedalBronze
import com.anarky.showtrack.core.designsystem.theme.MedalSilver
import com.anarky.showtrack.core.designsystem.theme.Paper10
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.LibraryEntry

private const val CENTRE_WEIGHT = 1.25f
private val MedalSize = 24.dp
private val MedalOverlap = 16.dp

/** Left to right on screen: #2, #1, #3, so the winner stands tallest in the middle. */
private val PodiumOrder = listOf(1, 0, 2)

/**
 * Your top three scored favourites. [ranked] is in rank order (#1 first; any beyond three are the
 * reserve and not drawn); places with
 * no entry stay empty, so one or two scored favourites still stand in their podium positions.
 *
 * Screen order and reading order differ on purpose: it is drawn #2, #1, #3, but TalkBack reads
 * #1, #2, #3 ("Number 1, Frieren, score 9.5").
 */
@Composable
internal fun FavoritesPodium(
    ranked: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val places = ranked.take(PODIUM_SIZE)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (places.size < PodiumOrder.size) {
            Text(
                text = stringResource(R.string.favorites_podium_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        ) {
            PodiumOrder.forEach { rank ->
                val weight = if (rank == 0) CENTRE_WEIGHT else 1f
                val entry = places.getOrNull(rank)
                if (entry == null) {
                    Spacer(modifier = Modifier.weight(weight))
                } else {
                    PodiumPlace(
                        entry = entry,
                        rank = rank + 1,
                        onOpen = { onOpen(entry) },
                        onRemove = { onRemove(entry) },
                        modifier = Modifier.weight(weight),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodiumPlace(
    entry: LibraryEntry,
    rank: Int,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        entry.media.title
            .substringBefore(':')
            .trim()
    val score = entry.score?.toPlainString().orEmpty()
    val spoken = stringResource(R.string.favorites_podium_place, rank, title, score)
    FavoriteMenuHost(onOpen = onOpen, onRemove = onRemove, modifier = modifier) { openMenu ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = openMenu,
                        onLongClickLabel = stringResource(R.string.favorites_remove_action),
                    ).clearAndSetSemantics {
                        contentDescription = spoken
                        // On the focusable node itself: set on a wrapper it would be flattened away
                        // and TalkBack would fall back to screen order (#2, #1, #3).
                        traversalIndex = rank.toFloat()
                    },
        ) {
            Box(contentAlignment = Alignment.BottomCenter) {
                MediaCover(coverImageUrl = entry.media.coverImageUrl, modifier = Modifier.fillMaxWidth())
                Medal(rank = rank, modifier = Modifier.offset(y = MedalOverlap))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MedalOverlap + 4.dp),
            )
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = StarGold)) { append("★ ") }
                        append(score)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Medal(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    val color =
        when (rank) {
            1 -> StarGold
            2 -> MedalSilver
            else -> MedalBronze
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(MedalSize).clip(CircleShape).background(color),
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Paper10,
        )
    }
}
