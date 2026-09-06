package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.Group

/**
 * The header row Feed and Groups both render when the signed-in account is in two or more groups
 * (decision E-B). In `:core:designsystem` (decision C-T) purely because it renders on both those
 * screens — there is nothing about it that is Feed- or Groups-specific.
 *
 * **The one-or-fewer-groups gate lives HERE, not at either call site** (E-K: "a switcher over a
 * single option is noise"): `return`ing before drawing anything for `groups.size < 2` is
 * [CountdownBadge]'s identical "render nothing" shape, and it means both callers can render this
 * unconditionally — passing whatever `activeGroupId`/`groups` they currently have — without each
 * one re-deriving the same visibility rule.
 *
 * [ScrollableTabRow]/[Tab], not [FilterChip][androidx.compose.material3.FilterChip] — [StatusTabRow]'s
 * identical reasoning: this is a single-selection row, one tab is always the active one, and `Tab`
 * is the child shape a `ScrollableTabRow` is designed around (its own `SecondaryIndicator` is the
 * only selection affordance, with no risk of a second one stacking on top of it).
 *
 * `activeGroupId` is a plain `String`, not nullable: both callers only ever render this once they
 * already know a group is active (`FeedScreen`/`GroupsScreen`'s own null checks around their calls
 * here) — a caller with no active group has nothing to hand this as the selection, and folding that
 * case into this component as a third rendering state would duplicate the no-groups empty state
 * each screen already owns.
 *
 * `group.name`, not an `R.string`, in the tab label (decision C-E is unaffected): a group's name is
 * server data the account chose, not static UI copy.
 */
@Composable
fun GroupSwitcher(
    groups: List<Group>,
    activeGroupId: String,
    onGroupSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.size < 2) return

    val selectedIndex = groups.indexOfFirst { group -> group.id == activeGroupId }.coerceAtLeast(minimumValue = 0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        edgePadding = 0.dp,
    ) {
        groups.forEach { group ->
            Tab(
                selected = group.id == activeGroupId,
                onClick = { onGroupSelected(group.id) },
                text = { Text(text = group.name, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}
