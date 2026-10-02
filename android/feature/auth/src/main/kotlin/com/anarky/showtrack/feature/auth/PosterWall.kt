package com.anarky.showtrack.feature.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val COLUMNS = 4
private const val ROWS = 4
private const val TILE_ASPECT = 1.5f
private const val ROTATION_DEGREES = -9f
private val OVERSCAN = 24.dp
private val TOP_OFFSET = (-40).dp
private val GAP = 8.dp
private val TILE_CORNER = 8.dp
private val TITLE_BAR_CORNER = 3.dp

/** CSS's 160°: a gradient line pointing down and slightly to the right. */
private const val GRADIENT_DEGREES = 160.0
private const val TITLE_BAR_WIDTH = 0.72f
private const val TITLE_BAR_HEIGHT = 0.06f
private const val TITLE_BAR_BOTTOM = 0.10f

/** The gradient that blends the poster wall into the form's solid fill. */
private val FADE_HEIGHT = 64.dp
private val TitleBarColor = Color.White.copy(alpha = 0.35f)

/**
 * The design's 16 hue pairs, row by row. Art, not theme: they exist only for this wall, are the
 * same in both themes, and never colour anything a user reads, so they stay out of `Color.kt`.
 */
private val HuePairs =
    listOf(
        268f to 330f,
        200f to 250f,
        20f to 350f,
        160f to 200f,
        40f to 10f,
        300f to 260f,
        190f to 140f,
        350f to 30f,
        230f to 280f,
        120f to 170f,
        10f to 50f,
        280f to 220f,
        170f to 210f,
        330f to 290f,
        60f to 30f,
        210f to 180f,
    )

private val TileColors =
    HuePairs.map { (top, bottom) ->
        Color.hsl(hue = top, saturation = 0.70f, lightness = 0.58f) to
            Color.hsl(hue = bottom, saturation = 0.65f, lightness = 0.32f)
    }

/**
 * The tilted wall of made-up posters behind the sign-in form. Drawn rather than loaded: nobody is
 * signed in yet, so there is no cover art the app could fetch.
 *
 * Meant to be sized to the whole screen and covered from below by the form. It never measures
 * the form or the keyboard, so neither can move it; the form decides how much of it shows.
 * A bare [Canvas] carries no semantics, so TalkBack skips it, which is what decoration should do.
 */
@Composable
internal fun PosterWall(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val gap = GAP.toPx()
        val gridLeft = -OVERSCAN.toPx()
        val gridWidth = size.width + 2 * OVERSCAN.toPx()
        val tileWidth = (gridWidth - (COLUMNS - 1) * gap) / COLUMNS
        val tileHeight = tileWidth * TILE_ASPECT
        val gridTop = TOP_OFFSET.toPx()
        val gridHeight = ROWS * tileHeight + (ROWS - 1) * gap
        val pivot = Offset(x = gridLeft + gridWidth / 2, y = gridTop + gridHeight / 2)

        rotate(degrees = ROTATION_DEGREES, pivot = pivot) {
            TileColors.forEachIndexed { index, colors ->
                val topLeft =
                    Offset(
                        x = gridLeft + (index % COLUMNS) * (tileWidth + gap),
                        y = gridTop + (index / COLUMNS) * (tileHeight + gap),
                    )
                drawPoster(topLeft = topLeft, size = Size(tileWidth, tileHeight), colors = colors)
            }
        }
    }
}

private fun DrawScope.drawPoster(
    topLeft: Offset,
    size: Size,
    colors: Pair<Color, Color>,
) {
    val (start, end) = cssGradientLine(topLeft = topLeft, size = size)
    drawRoundRect(
        brush = Brush.linearGradient(colors = listOf(colors.first, colors.second), start = start, end = end),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(TILE_CORNER.toPx()),
    )
    val barSize = Size(width = size.width * TITLE_BAR_WIDTH, height = size.height * TITLE_BAR_HEIGHT)
    drawRoundRect(
        color = TitleBarColor,
        topLeft =
            Offset(
                x = topLeft.x + (size.width - barSize.width) / 2,
                y = topLeft.y + size.height * (1 - TITLE_BAR_BOTTOM) - barSize.height,
            ),
        size = barSize,
        cornerRadius = CornerRadius(TITLE_BAR_CORNER.toPx()),
    )
}

/**
 * The wall fading into the form. Part of the form's own column, not drawn on the screen, so it
 * follows the form wherever the mode switch, the keyboard or a small screen puts it — and the
 * wordmark below it always sits on the solid fill, readable in both themes.
 */
@Composable
internal fun FormFade() {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(FADE_HEIGHT)
                .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
    )
}

/**
 * Start and end of a CSS `linear-gradient(160deg, …)` over a box: the line runs through the
 * centre at that angle, long enough that both corners it points between land exactly on its ends.
 */
private fun cssGradientLine(
    topLeft: Offset,
    size: Size,
): Pair<Offset, Offset> {
    val radians = Math.toRadians(GRADIENT_DEGREES)
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()
    val halfLength = (abs(size.width * dx) + abs(size.height * dy)) / 2
    val center = Offset(topLeft.x + size.width / 2, topLeft.y + size.height / 2)
    val half = Offset(dx * halfLength, dy * halfLength)
    return (center - half) to (center + half)
}
