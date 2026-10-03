package com.anarky.showtrack.feature.groups

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.UserAvatar
import com.anarky.showtrack.core.designsystem.theme.Paper10
import com.anarky.showtrack.core.designsystem.theme.StarGold
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MemberAvatarSize = 32.dp

/**
 * The Members tab: avatar, name ("you" on your own row), when they joined, and a role chip. The
 * owner gets a ⋯ with Remove from group on every row except their own (E-F: removing yourself is
 * Leave group, in the page menu). A member sees no ⋯ at all, and neither does anyone while the
 * viewer's role is still unknown: owner-only controls are hidden, never disabled.
 */
internal fun LazyListScope.membersItems(
    members: List<GroupMember>,
    currentUserId: String?,
    isOwner: Boolean,
    onRemoveClick: (GroupMember) -> Unit,
) {
    // Keyed by userId: without a key a reorder from a refresh re-uses the wrong row's state.
    items(items = members, key = { "member:${it.userId}" }) { member ->
        MemberRow(
            member = member,
            isYou = member.userId == currentUserId,
            showRemove = isOwner && member.userId != currentUserId,
            onRemoveClick = { onRemoveClick(member) },
        )
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    isYou: Boolean,
    showRemove: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val joined =
        member.joinedAt
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMyyyy"), locale))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        UserAvatar(userId = member.userId, name = member.username, size = MemberAvatarSize)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isYou) stringResource(R.string.groups_member_name_you, member.username) else member.username,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.groups_member_joined, joined),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RoleChip(role = member.role)
        if (showRemove) {
            MemberMenu(username = member.username, onRemoveClick = onRemoveClick)
        } else {
            // Keeps the chips aligned whether or not a row has a menu.
            Box(modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** Owner in gold, Member in grey. */
@Composable
private fun RoleChip(role: GroupRole) {
    val owner = role == GroupRole.OWNER
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (owner) StarGold else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (owner) Paper10 else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(role.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun MemberMenu(
    username: String,
    onRemoveClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.groups_member_more, username),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.groups_detail_remove_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    onRemoveClick()
                },
            )
        }
    }
}
