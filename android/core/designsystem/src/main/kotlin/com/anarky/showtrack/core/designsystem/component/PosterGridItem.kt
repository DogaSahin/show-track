package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The pill sits over cover art of any colour, so its background is a near-opaque `surface` scrim
// rather than a tint: the teal text has to read on a white poster and on a black one alike.
private const val PILL_SCRIM_ALPHA = 0.88f

/**
 * One title in a poster grid: a 2:3 cover filling the cell's width, the title under it on one
 * line, and an optional [label] pill across the top of the cover ("S3 soon").
 *
 * Width is the caller's (a grid cell); the cover's aspect ratio derives the height, so every cell
 * in a row lines up whatever art has loaded. The cover itself is decorative ([MediaCover] passes
 * no content description); `clickable` merges the cell, so TalkBack reads the title and the label as
 * one tappable item.
 */
@Composable
fun PosterGridItem(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(space = 4.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            MediaCover(coverImageUrl = coverUrl, modifier = Modifier.fillMaxWidth())
            label?.let { PosterPill(text = it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PosterPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = PILL_SCRIM_ALPHA),
        contentColor = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
