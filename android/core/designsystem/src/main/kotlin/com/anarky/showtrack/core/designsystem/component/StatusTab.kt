package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/**
 * The full row of [StatusTab]s, one per [UserMediaStatus]. [ScrollableTabRow] rather than a fixed
 * `TabRow` (decision C-J): five tabs do not fit a 360dp-wide screen, and a fixed `TabRow` divides
 * its width evenly across every tab and truncates each label instead of letting the row scroll.
 * `edgePadding = 0.dp` so the row's own leading tab lines up with the rest of the screen's content
 * instead of Material's default tab-row inset.
 */
@Composable
fun StatusTabRow(
    selectedStatus: UserMediaStatus,
    onStatusSelected: (UserMediaStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statuses = UserMediaStatus.entries
    ScrollableTabRow(
        selectedTabIndex = statuses.indexOf(selectedStatus),
        modifier = modifier,
        edgePadding = 0.dp,
    ) {
        statuses.forEach { status ->
            StatusTab(
                status = status,
                selected = status == selectedStatus,
                onClick = { onStatusSelected(status) },
            )
        }
    }
}
