package com.anarky.showtrack.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.typeAndYear
import com.anarky.showtrack.core.model.Recommendation

private val PosterWidth = 96.dp
private val AddButtonSize = 28.dp
private const val MAX_SHARED_GENRES = 2

/**
 * One seed's worth of recommendations, grouped for rendering.
 *
 * [seedMediaId] is the identity, not [seedTitle]: two different seed titles can collide as strings
 * (a remake and its original share a name often enough), and grouping by the display string would
 * silently merge two unrelated shelves into one. The title is carried alongside purely so the
 * header has something to render without a second lookup.
 */
internal data class DiscoverShelf(
    val seedMediaId: String,
    val seedTitle: String,
    val items: List<Recommendation>,
)

/**
 * Groups a flat, score-ordered page of recommendations into per-seed shelves.
 *
 * `groupBy` rather than a sort: `LinkedHashMap` preserves first-encounter order, so shelf order is
 * "strongest recommendation first" — the ranking the server already computed — and item order
 * inside a shelf is the server's too. Sorting shelves by size, or alphabetically, would discard
 * that ranking for an ordering nobody asked for.
 *
 * **This grouping is over whatever has been paged in so far, and that has a visible consequence.**
 * The API returns recommendations ordered by blended score, not grouped by seed (see the backend's
 * `RecommendationItem` — the ordering IS the score), so a second page usually lands inside shelves
 * the reader has already scrolled past: those shelves grow behind them rather than new ones
 * appearing at the bottom. That is the honest cost of the shelf layout over this endpoint. It is
 * survivable — a shelf scrolls horizontally, so growth costs no vertical space and moves nothing —
 * but it is also why `DiscoverList` passes the flat item count as `EndOfListTrigger`'s `rearmKey`:
 * without it, a page that adds no shelf leaves the trigger latched and paging stops.
 */
internal fun List<Recommendation>.toShelves(): List<DiscoverShelf> =
    groupBy { it.reason.seedMediaId }
        .map { (seedMediaId, items) ->
            DiscoverShelf(
                seedMediaId = seedMediaId,
                seedTitle = items.first().reason.seedTitle,
                items = items,
            )
        }

/**
 * The genres this shelf has in common: the most frequent `matchedGenres` across its items, at most
 * [MAX_SHARED_GENRES], ties going to the one seen first (the server's ranking).
 */
internal fun DiscoverShelf.sharedGenres(): List<String> =
    items
        .flatMap { it.reason.matchedGenres }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(MAX_SHARED_GENRES)
        .map { it.key }

/**
 * A shelf: the seed named once in a header, then its titles as posters in a [LazyRow].
 *
 * The reason moves from a line under every row into the header, which is what buys the poster
 * layout its space — "because you watched Frieren" repeated eight times down a column was the
 * single least informative thing on the old screen, since a strong seed produces a long run of
 * consecutive rows that all say it.
 *
 * Under the seed, the one or two genres the whole row shares ("Adventure · Fantasy"), not every
 * item's own list: five stacked genre lists is noise, one shared line is the reason in brief.
 */
@Composable
internal fun DiscoverShelfRow(
    shelf: DiscoverShelf,
    onItemClick: (Recommendation) -> Unit,
    onAdd: (Recommendation) -> Unit,
    addErrorMediaId: String?,
    modifier: Modifier = Modifier,
) {
    val sharedGenres = remember(shelf) { shelf.sharedGenres() }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 10.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.discover_shelf_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = shelf.seedTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sharedGenres.isNotEmpty()) {
                Text(
                    text = sharedGenres.joinToString(separator = " · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(space = 10.dp),
        ) {
            // Keyed by media id for the same reason the old LazyColumn was: a recommendation's
            // media IS persisted and carries a stable id (decision C-N), and the optimistic
            // remove/restore on add (decision D-I) reorders exactly this list.
            items(items = shelf.items, key = { it.media.id }) { recommendation ->
                DiscoverPoster(
                    recommendation = recommendation,
                    onClick = { onItemClick(recommendation) },
                    onAdd = { onAdd(recommendation) },
                    showAddError = addErrorMediaId == recommendation.media.id,
                )
            }
        }
    }
}

/**
 * One poster.
 *
 * The add button is overlaid on the cover rather than trailing the title, because at this width
 * there is no trailing space left — and putting it on the art keeps it in the same place on every
 * card regardless of how many lines the title takes.
 *
 * A failed add renders as a line UNDER this card, not as a dialog or a snackbar: decision D-I
 * removes the row optimistically and restores it on failure, so the card the message belongs to is
 * back on screen and is the only place the message means anything.
 */
@Composable
private fun DiscoverPoster(
    recommendation: Recommendation,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    showAddError: Boolean,
) {
    val media = recommendation.media
    Column(
        modifier = Modifier.width(PosterWidth).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(space = 6.dp),
    ) {
        Box {
            MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.fillMaxWidth())
            Surface(
                onClick = onAdd,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(all = 5.dp)
                        .size(AddButtonSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription =
                            stringResource(R.string.discover_add_content_description, media.title),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // minLines AND maxLines both 2: the title block is a fixed two lines tall whether or not
        // the title needs them, so the caption below it lands on the same baseline for every card in
        // a shelf. Without the floor, one wrapping title pushes its own year down and the row's
        // metadata stops reading as a row.
        Text(
            text = media.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = media.typeAndYear(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showAddError) {
            Text(
                text = stringResource(R.string.discover_add_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onAdd),
            )
        }
    }
}
