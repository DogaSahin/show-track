package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.R
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
 * The full row of tabs: "All" (`selected == null`) plus one per [UserMediaStatus]. [ScrollableTabRow]
 * rather than a fixed `TabRow` (decision C-J): six tabs do not fit a 360dp-wide screen, and a fixed
 * `TabRow` divides its width evenly across every tab and truncates each label instead of letting
 * the row scroll. `edgePadding = 0.dp` so the row's own leading tab lines up with the rest of the
 * screen's content instead of Material's default tab-row inset.
 *
 * [selected] is nullable — `null` means "All", the library screen's default, unfiltered view. That
 * concept belongs here rather than being synthesized by every caller (e.g. a sixth
 * `UserMediaStatus.ALL` entry, which would then have to be excluded from every OTHER place the enum
 * is switched over) because "All" is a property of the row's selection, not of the status domain.
 *
 * **Tab, not FilterChip, as of task 9a.8's review of 9a.6's version of this row.** The previous
 * version put a [FilterChip] inside this [ScrollableTabRow], which stacks two selection
 * affordances: the row itself draws a `SecondaryIndicator` underline plus a bottom divider under
 * the selected slot, while each chip ALSO draws its own selected fill — and the row's minimum tab
 * width padded narrow chips out besides. [Tab] is the child shape `ScrollableTabRow` is actually
 * designed around, so the indicator is the only selection affordance and there is no padding
 * mismatch. [StatusTab] (the standalone [FilterChip]) is unchanged and still exported for a
 * context that wants a filter chip rather than a tab.
 */
@Composable
fun StatusTabRow(
    selected: UserMediaStatus?,
    onStatusSelected: (UserMediaStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statuses = UserMediaStatus.entries
    // "All" is prepended at index 0, ahead of one tab per status, so the two never collide.
    val selectedIndex = if (selected == null) 0 else statuses.indexOf(selected) + 1
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        edgePadding = 0.dp,
    ) {
        Tab(
            selected = selected == null,
            onClick = { onStatusSelected(null) },
            text = { Text(text = stringResource(R.string.status_all), style = MaterialTheme.typography.labelLarge) },
        )
        statuses.forEach { status ->
            Tab(
                selected = status == selected,
                onClick = { onStatusSelected(status) },
                text = { Text(text = status.label(), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}
