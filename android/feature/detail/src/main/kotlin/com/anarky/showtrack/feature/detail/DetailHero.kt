package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.BlurredCoverBackdrop
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.countdownLabel
import com.anarky.showtrack.core.designsystem.component.nextEpisodeLabel
import com.anarky.showtrack.core.designsystem.component.typeAndYear
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaStatus

private val BackdropHeight = 200.dp
private val CoverWidth = 84.dp
private val CoverHeight = 126.dp
private val CoverOverlap = 92.dp
private val OverlayButtonSize = 34.dp
private val OverlayIconSize = 20.dp
private const val OVERLAY_SCRIM_ALPHA = 0.45f
private const val BACKDROP_FADE_START = 0.3f
private const val NEXT_BAR_TINT_ALPHA = 0.5f

/** Backdrop with the overlapping cover, then title and "TV · 2022 · Airing". */
@Composable
internal fun DetailHero(
    media: Media,
    modifier: Modifier = Modifier,
) {
    // One Box: the cover row starts inside the backdrop, so the Box measures to the row's bottom
    // and nothing below is pushed down by the overlap.
    Box(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(BackdropHeight)) {
            BlurredCoverBackdrop(coverImageUrl = media.coverImageUrl, modifier = Modifier.matchParentSize())
            val background = MaterialTheme.colorScheme.background
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                BACKDROP_FADE_START to Color.Transparent,
                                1f to background,
                            ),
                        ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = BackdropHeight - CoverOverlap),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MediaCover(
                coverImageUrl = media.coverImageUrl,
                modifier = Modifier.width(CoverWidth).height(CoverHeight).clip(MaterialTheme.shapes.small),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.detail_hero_meta, media.typeAndYear(), media.status.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaStatus.label(): String =
    stringResource(
        when (this) {
            MediaStatus.AIRING -> R.string.detail_media_status_airing
            MediaStatus.FINISHED -> R.string.detail_media_status_finished
            MediaStatus.NOT_YET_AIRED -> R.string.detail_media_status_upcoming
        },
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreChips(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        genres.forEach { genre ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** "Next: S2 E7 · Tomorrow". Hidden when nothing is scheduled. */
@Composable
internal fun NextEpisodeBar(
    media: Media,
    modifier: Modifier = Modifier,
) {
    val label = media.nextEpisodeLabel() ?: return
    val days = media.daysUntilNextEpisode
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = NEXT_BAR_TINT_ALPHA),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detail_next_episode, label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (days != null) {
                Text(
                    text = countdownLabel(days),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Back and ⋯ on 34 dp dark circles, so they read over any cover; the touch target stays 48 dp. */
@Composable
internal fun OverlayIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA),
        contentColor = Color.White,
        modifier = modifier.minimumInteractiveComponentSize().size(OverlayButtonSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(OverlayIconSize),
            )
        }
    }
}
