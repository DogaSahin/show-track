package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.UserMediaStatus
import java.math.BigDecimal

// 2:3 is the standard poster/key-art proportion (AniList and TMDB cover art both ship close to
// it), fixed so a card never resizes as art finishes loading in around it.
private const val POSTER_ASPECT_RATIO = 2f / 3f

// 58dp, and the number is a consequence rather than a taste. At a fixed 2:3 ratio the poster's
// WIDTH sets the row height (58 -> 87dp), and the text beside it never needs that much, so the
// poster alone decides how many titles fit on screen. 76dp made a 114dp poster and a 134dp row —
// four and a bit visible. 58dp gives a ~107dp row and seven. "Bigger poster" and "denser list" are
// the same dial turned opposite ways; density won because a library is a list you scan.
private val PosterWidth = 58.dp

/**
 * A row card for one [Media] title: poster, title, year, the user's library status (if any) and
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(PosterWidth))
        Column(
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(space = 5.dp),
        ) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Year and status on one line, separated by a dot: two facts of equal weight about the
            // same title, where a badge on its own row made the status look like the row's subject.
            Row(verticalAlignment = Alignment.CenterVertically) {
                media.year?.let { year ->
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (media.year != null && status != null) {
                    Text(
                        text = METADATA_SEPARATOR,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                status?.let { StatusLabel(status = it) }
            }
        }
        score?.let {
            ScoreChip(score = it, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private const val METADATA_SEPARATOR = "\u00b7"

/**
 * The poster slot: exported (not `private`) because [coverImageUrl] is loaded through Coil's
 * [AsyncImage], and Coil is `implementation`-scoped inside this module — no `:feature:*` module
 * has it on its compile classpath (architecture rule 2). This is the design system's only cover-art
 * primitive, so a screen showing one title full-size (`:feature:detail`) reuses this instead of
 * either depending on Coil directly (which the build would refuse) or growing a second,
 * near-identical placeholder/loading painter of its own. Sizing is entirely the caller's: pass a
 * `width` (or any other size) through [modifier] and the aspect ratio derives the rest.
 *
 * [placeholder], [error] and [fallback] all point at the same [rememberPosterPlaceholder] painter,
 * so a still-loading cover, a failed fetch and a genuinely absent URL (as in `previewMedia`, whose
 * whole point is to exercise this without a network) all resolve to one deliberate, theme-coloured
 * mark rather than three different broken-looking states.
 */
@Composable
fun MediaCover(
    coverImageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val placeholder = rememberPosterPlaceholder()
    AsyncImage(
        model = coverImageUrl,
        contentDescription = null,
        modifier =
            modifier
                .aspectRatio(ratio = POSTER_ASPECT_RATIO)
                .clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
    )
}

@Composable
private fun rememberPosterPlaceholder(): Painter {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PLACEHOLDER_MARK_ALPHA)
    return remember(backgroundColor, markColor) { PosterPlaceholderPainter(backgroundColor, markColor) }
}

private const val PLACEHOLDER_MARK_ALPHA = 0.24f

/** A theme-coloured field with a smaller centred mark — reads as "poster goes here," not a hole. */
private class PosterPlaceholderPainter(
    private val backgroundColor: Color,
    private val markColor: Color,
) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawRect(color = backgroundColor)
        val markSize = Size(size.width * MARK_WIDTH_FRACTION, size.height * MARK_HEIGHT_FRACTION)
        drawRoundRect(
            color = markColor,
            topLeft = Offset((size.width - markSize.width) / 2f, (size.height - markSize.height) / 2f),
            size = markSize,
            cornerRadius = CornerRadius(markSize.minDimension * MARK_CORNER_FRACTION),
        )
    }

    private companion object {
        const val MARK_WIDTH_FRACTION = 0.4f
        const val MARK_HEIGHT_FRACTION = 0.55f
        const val MARK_CORNER_FRACTION = 0.15f
    }
}

/**
 * A colour dot plus the status name, not a filled pill.
 *
 * Five saturated pills running down a list compete with each other and with the cover art beside
 * them, and the pill's fill was doing no work the label was not already doing. The dot keeps the
 * colour coding — same [markColor] mapping, so the five statuses stay distinguishable at a
 * glance — while spending a fraction of the visual weight on it.
 */
@Composable
private fun StatusLabel(
    status: UserMediaStatus,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(StatusDotSize)
                    .clip(CircleShape)
                    .background(status.markColor()),
        )
        Text(
            text = status.label(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private val StatusDotSize = 7.dp
