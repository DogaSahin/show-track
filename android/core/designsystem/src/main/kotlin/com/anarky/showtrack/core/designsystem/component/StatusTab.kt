package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anarky.showtrack.core.model.UserMediaStatus

/**
 * A single selectable filter tab for one [UserMediaStatus] — e.g. filtering the library screen
 * down to "Watching". This is a filter control, not a badge: it is not reused inside [MediaCard],
 * whose status indicator is read-only.
 */
@Composable
fun StatusTab(
    status: UserMediaStatus,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = status.label(), style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
    )
}
