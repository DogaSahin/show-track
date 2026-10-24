package com.anarky.showtrack.core.designsystem.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val BLUR_RADIUS = 20.dp
private const val BACKDROP_SCALE = 1.3f
private const val BACKDROP_ALPHA = 0.35f

// Below Android 12 `Modifier.blur` draws nothing different, so the cover would show sharp. A
// fainter one keeps it reading as texture rather than as a second picture behind the card.
private const val UNBLURRED_BACKDROP_ALPHA = 0.18f
private const val SCRIM_START = 0.3f
private const val SCRIM_END = 0.75f
private const val SCRIM_ALPHA = 0.7f

/**
 * A title's cover, stretched and blurred, as the background of a card or header: the "cover does
 * the visual work" look Discover's top pick and the show details header share. Fill the parent with
 * it (`Modifier.matchParentSize()`) and clip the parent.
 *
 * The `surface` scrim from the middle to the right edge is what keeps text over it readable in both
 * themes whatever the cover's colours, and it is the only guarantee: the blur itself only exists on
 * Android 12+, older devices get a fainter, unblurred cover instead.
 */
@Composable
fun BlurredCoverBackdrop(
    coverImageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = modifier) {
        AsyncImage(
            model = coverImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = BACKDROP_SCALE
                        scaleY = BACKDROP_SCALE
                    }.blur(radius = BLUR_RADIUS, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .alpha(if (canBlur) BACKDROP_ALPHA else UNBLURRED_BACKDROP_ALPHA),
        )
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            SCRIM_START to surface.copy(alpha = 0f),
                            SCRIM_END to surface.copy(alpha = SCRIM_ALPHA),
                            1f to surface.copy(alpha = SCRIM_ALPHA),
                        ),
                    ),
        )
    }
}
