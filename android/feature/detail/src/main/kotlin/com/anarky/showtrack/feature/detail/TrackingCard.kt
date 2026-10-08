package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.ScoreChip
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.designsystem.theme.FavoriteHeart
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.UserMediaStatus
import java.math.BigDecimal

private val ScoreOptions: List<BigDecimal> = (2..20).map { half -> BigDecimal(half).divide(BigDecimal(2)).setScale(1) }
private val StatusDotSize = 8.dp
private val ProgressBarHeight = 4.dp

/** Everything you change about the title, in one card. */
@Suppress("LongParameterList")
@Composable
internal fun TrackingCard(
    entry: LibraryEntry,
    totalEpisodes: Int?,
    saving: Boolean,
    error: DetailActionError?,
    onStatusSelected: (UserMediaStatus) -> Unit,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDropdown(status = entry.status, enabled = !saving, onStatusSelected = onStatusSelected)
                ScoreDropdown(
                    score = entry.score,
                    enabled = !saving,
                    onScoreSelected = onScoreSelected,
                    onScoreCleared = onScoreCleared,
                )
                Spacer(modifier = Modifier.weight(1f))
                FavoriteButton(favorite = entry.favorite, enabled = !saving, onToggle = onFavoriteToggle)
            }
            WatchedProgress(progress = entry.progress, total = totalEpisodes)
            if (error is DetailActionError.Edit || error is DetailActionError.Remove) {
                Text(
                    text =
                        stringResource(
                            if (error is DetailActionError.Remove) {
                                R.string.detail_remove_error
                            } else {
                                R.string.detail_edit_error
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusDropdown(
    status: UserMediaStatus,
    enabled: Boolean,
    onStatusSelected: (UserMediaStatus) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(status)
                Text(text = status.label(), style = MaterialTheme.typography.labelLarge)
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            UserMediaStatus.entries.forEach { option ->
                DropdownMenuItem(
                    leadingIcon = { StatusDot(option) },
                    text = { Text(text = option.label()) },
                    onClick = {
                        open = false
                        if (option != status) onStatusSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: UserMediaStatus) {
    Box(modifier = Modifier.size(StatusDotSize).clip(CircleShape).background(status.markColor()))
}

@Composable
private fun ScoreDropdown(
    score: BigDecimal?,
    enabled: Boolean,
    onScoreSelected: (BigDecimal) -> Unit,
    onScoreCleared: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = stringResource(R.string.detail_score_button)
        ScoreChip(
            score = score,
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScoreOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option.toPlainString()) },
                    onClick = {
                        open = false
                        onScoreSelected(option)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.detail_score_clear)) },
                onClick = {
                    open = false
                    onScoreCleared()
                },
            )
        }
    }
}

@Composable
private fun FavoriteButton(
    favorite: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val description = stringResource(R.string.detail_favorite_content_description)
    val state = stringResource(if (favorite) R.string.detail_favorite_on else R.string.detail_favorite_off)
    IconButton(
        onClick = onToggle,
        enabled = enabled,
        modifier =
            Modifier.semantics {
                contentDescription = description
                stateDescription = state
            },
    ) {
        Icon(
            painter = painterResource(if (favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
            contentDescription = null,
            tint = if (favorite) FavoriteHeart else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "15 of 19 episodes watched" with a bar, or "15 episodes watched" while the total is unknown. */
@Composable
private fun WatchedProgress(
    progress: Int,
    total: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text =
                if (total != null && total > 0) {
                    stringResource(R.string.detail_episodes_watched_of, progress, total)
                } else {
                    pluralStringResource(R.plurals.detail_episodes_watched, progress, progress)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (progress.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(ProgressBarHeight),
                drawStopIndicator = {},
            )
        }
    }
}

/** Not in the library yet: one button where the card goes. */
@Composable
internal fun AddToLibraryButton(
    saving: Boolean,
    error: DetailActionError.Add?,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onAdd, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.detail_add_button))
        }
        if (error != null) {
            Text(
                text = stringResource(R.string.detail_add_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
