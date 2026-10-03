package com.anarky.showtrack.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.GroupAvatar
import com.anarky.showtrack.core.model.Group

private val SwitcherMaxWidth = 180.dp

/**
 * The title bar's group switcher: the active group's avatar and name in a rounded pill, opening a
 * menu of every group (the active one highlighted) with Manage groups at the bottom.
 *
 * Shown even with a single group, unlike the old tab row: the pill also says WHICH group this feed
 * is, and the menu is the way to Groups. Each group shows its member count when the groups list
 * carries one.
 */
@Composable
internal fun FeedGroupSwitcher(
    groups: List<Group>,
    activeGroupId: String,
    onSwitchGroup: (String) -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = groups.find { it.id == activeGroupId } ?: return
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.feed_switch_group, active.name)

    Box(modifier = modifier.padding(end = 8.dp)) {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space = 6.dp),
                modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                GroupAvatar(groupId = active.id, name = active.name, size = 20.dp)
                Text(
                    text = active.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SwitcherMaxWidth),
                )
                Text(text = "▾", style = MaterialTheme.typography.labelLarge)
            }
        }
        GroupMenu(
            expanded = expanded,
            groups = groups,
            activeGroupId = activeGroupId,
            onDismiss = { expanded = false },
            onSwitchGroup = onSwitchGroup,
            onManageGroups = onManageGroups,
        )
    }
}

/** Every group, the active one highlighted, then Manage groups. Picking the active group just closes it. */
@Suppress("LongParameterList")
@Composable
private fun GroupMenu(
    expanded: Boolean,
    groups: List<Group>,
    activeGroupId: String,
    onDismiss: () -> Unit,
    onSwitchGroup: (String) -> Unit,
    onManageGroups: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        groups.forEach { group ->
            val isActive = group.id == activeGroupId
            val nameColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = {
                    Text(
                        text = group.name,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = nameColor,
                    )
                },
                leadingIcon = { GroupAvatar(groupId = group.id, name = group.name, size = 24.dp) },
                trailingIcon =
                    group.memberCount?.let { count ->
                        {
                            val spoken = pluralStringResource(R.plurals.feed_group_members, count, count)
                            Text(
                                text = count.toString(),
                                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                onClick = {
                    onDismiss()
                    if (!isActive) onSwitchGroup(group.id)
                },
                modifier = Modifier.semantics { selected = isActive },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(text = stringResource(R.string.feed_manage_groups), color = MaterialTheme.colorScheme.primary)
            },
            onClick = {
                onDismiss()
                onManageGroups()
            },
        )
    }
}
