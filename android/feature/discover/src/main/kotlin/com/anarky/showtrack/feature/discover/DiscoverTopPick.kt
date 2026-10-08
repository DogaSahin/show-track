package com.anarky.showtrack.feature.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.BlurredCoverBackdrop
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.typeAndYear
import com.anarky.showtrack.core.model.Recommendation

private const val MAX_GENRE_CHIPS = 4
private val TopPickCoverWidth = 92.dp
private val TopPickButtonHeight = 40.dp

/**
 * What the screen shows, derived from the flat feed: its first item as the top pick, and every
 * other item grouped into shelves.
 *
 * The server already orders recommendations best first, so "first" is "best". Taking it out before
 * grouping is what keeps it from also appearing in its shelf, and a shelf whose only item was the
 * top pick simply never forms. Nothing here is stored: adding the top pick removes it from the feed
 * (the ViewModel's optimistic removal), and the next item becomes the top pick on the next
 * derivation; a failed add restores it to index 0, which makes it the top pick again.
 */
internal data class DiscoverLayout(
    val topPick: Recommendation?,
    val shelves: List<DiscoverShelf>,
)

internal fun List<Recommendation>.toDiscoverLayout(): DiscoverLayout =
    DiscoverLayout(topPick = firstOrNull(), shelves = drop(1).toShelves())

/** One genre chip on the top pick, highlighted when it is one of the reasons it was picked. */
internal data class GenreChip(
    val name: String,
    val matched: Boolean,
)

/**
 * The title's genres with the matched ones first, at most [MAX_GENRE_CHIPS]. Compared without case:
 * the two lists come from different parts of the response and nothing promises they agree on it.
 */
internal fun Recommendation.genreChips(): List<GenreChip> {
    val matched = reason.matchedGenres.map { it.lowercase() }.toSet()
    return media.genres
        .map { GenreChip(name = it, matched = it.lowercase() in matched) }
        .sortedByDescending { it.matched }
        .take(MAX_GENRE_CHIPS)
}

/**
 * The card at the top of Discover: blurred cover behind, sharp cover, why it was picked, its genres,
 * and Add / Details.
 *
 * The text block is ONE accessibility node ("Top pick for you, Frieren, Anime · 2023, Because you
 * watched Made in Abyss"), followed by the two buttons. Read piece by piece it would be five stops
 * before the reader reaches anything they can act on.
 */
@Composable
internal fun TopPickCard(
    recommendation: Recommendation,
    showAddError: Boolean,
    onAdd: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Box {
            BlurredCoverBackdrop(
                coverImageUrl = recommendation.media.coverImageUrl,
                modifier = Modifier.matchParentSize(),
            )
            Column {
                TopPickSummary(recommendation = recommendation, showAddError = showAddError, onClick = onDetails)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Button(
                        onClick = onAdd,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(TopPickButtonHeight),
                    ) {
                        Text(text = stringResource(R.string.discover_add_button))
                    }
                    OutlinedButton(
                        onClick = onDetails,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(TopPickButtonHeight),
                    ) {
                        Text(text = stringResource(R.string.discover_details_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopPickSummary(
    recommendation: Recommendation,
    showAddError: Boolean,
    onClick: () -> Unit,
) {
    val media = recommendation.media
    val label = stringResource(R.string.discover_top_pick_label)
    val meta = media.typeAndYear()
    val seed = recommendation.reason.seedTitle
    val reasonText = stringResource(R.string.discover_top_pick_reason, seed)
    val error = if (showAddError) stringResource(R.string.discover_add_error) else null
    val spoken = listOfNotNull(label, media.title, meta, reasonText, error).joinToString(separator = ", ")

    Row(
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(all = 14.dp),
    ) {
        MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(TopPickCoverWidth))
        Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
            Text(
                // Uppercased in the layout, stored in normal case: the spoken text above stays readable.
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = reasonText.withEmphasis(seed, MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            GenreChips(chips = recommendation.genreChips())
        }
    }
}

@Composable
private fun GenreChips(chips: List<GenreChip>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(space = 6.dp),
        verticalArrangement = Arrangement.spacedBy(space = 6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        chips.forEach { chip ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color =
                    if (chip.matched) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                contentColor =
                    if (chip.matched) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ) {
                Text(
                    text = chip.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** [this] with the first occurrence of [part] emphasised: the seed title inside "Because you watched…". */
private fun String.withEmphasis(
    part: String,
    color: Color,
) = buildAnnotatedString {
    val start = indexOf(part)
    if (start < 0 || part.isEmpty()) {
        append(this@withEmphasis)
    } else {
        append(substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) {
            append(part)
        }
        append(substring(start + part.length))
    }
}
