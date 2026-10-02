package com.anarky.showtrack.feature.feed

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.ActivityKind

/** A fully rounded chip with the numbers behind the sentence. */
@Composable
internal fun DetailChip(detail: FeedDetail) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text = detail.visibleText(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = detail.textColor(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FeedDetail.visibleText() =
    buildAnnotatedString {
        when (this@visibleText) {
            is FeedDetail.Progress ->
                append(
                    if (from == null) {
                        stringResource(R.string.feed_progress_single, to)
                    } else {
                        stringResource(R.string.feed_progress_range, from, to)
                    },
                )
            is FeedDetail.Score -> {
                withStyle(SpanStyle(color = StarGold)) { append("★ ") }
                append(value)
            }
            is FeedDetail.DroppedAt -> append(stringResource(R.string.feed_dropped_at, episode))
            is FeedDetail.Status -> append(status.label())
        }
    }

@Composable
internal fun FeedDetail.spokenText(): String =
    when (this) {
        is FeedDetail.Progress ->
            if (from == null) {
                stringResource(R.string.feed_progress_single_spoken, to)
            } else {
                stringResource(R.string.feed_progress_range_spoken, from, to)
            }
        is FeedDetail.Score -> stringResource(R.string.feed_score_spoken, value)
        is FeedDetail.DroppedAt -> stringResource(R.string.feed_dropped_at_spoken, episode)
        is FeedDetail.Status -> status.label()
    }

@Composable
private fun FeedDetail.textColor(): Color =
    when (this) {
        is FeedDetail.Score ->
            if (kind ==
                ActivityKind.COMPLETED
            ) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        is FeedDetail.DroppedAt -> MaterialTheme.colorScheme.error
        is FeedDetail.Status -> MaterialTheme.colorScheme.primary
        is FeedDetail.Progress -> MaterialTheme.colorScheme.onSurface
    }
