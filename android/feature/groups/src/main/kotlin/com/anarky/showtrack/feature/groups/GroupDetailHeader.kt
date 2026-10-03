package com.anarky.showtrack.feature.groups

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.designsystem.component.AvatarStack
import com.anarky.showtrack.core.designsystem.component.GroupAvatar
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupMember
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private val HeaderAvatarSize = 52.dp
private val ShareButtonSize = 36.dp
private const val CODE_GROUP = 4
private const val HOURS_PER_DAY = 24
private const val CLOCK_TICK_MS = 60_000L

/** The page header: group avatar, name, member faces and count. No buttons. */
@Composable
internal fun GroupHeader(
    groupId: String,
    name: String,
    members: List<GroupMember>,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 14.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        GroupAvatar(groupId = groupId, name = name, size = HeaderAvatarSize)
        Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
            Text(text = name, style = MaterialTheme.typography.headlineSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
            ) {
                AvatarStack(people = members.map { GroupActor(id = it.userId, username = it.username) })
                Text(
                    text = pluralStringResource(R.plurals.groups_card_members, members.size, members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The owner's invite strip: the current code, how long it works, and a share button. Long-press
 * the code to copy it (marked sensitive on the clipboard). An expired code turns the strip red with
 * New code; without a code to show (the read failed), it is "Invite people" with New code.
 *
 * TalkBack reads the code one character at a time ("K 7 Q 2 …"): read as a word it is noise.
 */
@Composable
internal fun InviteStrip(
    groupName: String,
    invite: InviteState,
    onNewCode: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Instant = Instant::now,
) {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = clock()
        }
    }
    val ready = invite as? InviteState.Ready
    val expired = ready != null && !ready.invite.expiresAt.isAfter(now)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (expired) colors.errorContainer else colors.primaryContainer,
        contentColor = if (expired) colors.onErrorContainer else colors.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            when {
                invite is InviteState.Unknown ->
                    Text(
                        text = stringResource(R.string.groups_invite_loading),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                ready == null -> {
                    Text(
                        text = stringResource(R.string.groups_invite_fallback),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewCode) { Text(text = stringResource(R.string.groups_invite_new_code)) }
                }
                expired -> {
                    InviteCode(
                        invite = ready.invite,
                        label = expiredLabel(ready.invite.expiresAt, now),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewCode) { Text(text = stringResource(R.string.groups_invite_new_code)) }
                }
                else -> {
                    InviteCode(
                        invite = ready.invite,
                        label = worksLabel(ready.invite.expiresAt, now),
                        modifier = Modifier.weight(1f),
                    )
                    ShareButton(groupName = groupName, code = ready.invite.inviteCode)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InviteCode(
    invite: GroupWithInvite,
    label: String,
    modifier: Modifier = Modifier,
) {
    var copied by remember(invite.inviteCode) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.groups_invite_clip_label)
    val spoken =
        stringResource(
            R.string.groups_invite_strip_spoken,
            invite.inviteCode.toCharArray().joinToString(separator = " "),
            label,
        )
    val copyLabel = stringResource(R.string.groups_invite_copy)
    Column(
        modifier =
            modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(sensitiveInviteCodeClipEntry(clipLabel, invite.inviteCode))
                            copied = true
                        }
                    },
                    onLongClickLabel = copyLabel,
                ).clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            text = if (copied) stringResource(R.string.groups_invite_copied) else label,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = invite.inviteCode.chunked(CODE_GROUP).joinToString(separator = "-"),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ShareButton(
    groupName: String,
    code: String,
) {
    val context = LocalContext.current
    val text =
        stringResource(
            R.string.groups_invite_share_text,
            groupName,
            code.chunked(CODE_GROUP).joinToString(separator = "-"),
        )
    val chooserTitle = stringResource(R.string.groups_invite_share)
    Surface(
        onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, chooserTitle))
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(ShareButtonSize),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_share),
            contentDescription = chooserTitle,
            modifier = Modifier.padding(all = 8.dp),
        )
    }
}

/** "Invite code · works 5 more days" (hours under a day, "less than an hour" at the end). */
@Composable
private fun worksLabel(
    expiresAt: Instant,
    now: Instant,
): String {
    val left = Duration.between(now, expiresAt)
    val hours = left.toHours().toInt()
    val days = (left.toHours() / HOURS_PER_DAY).toInt()
    val works =
        when {
            days >= 1 -> pluralStringResource(R.plurals.groups_invite_works_days, days, days)
            hours >= 1 -> pluralStringResource(R.plurals.groups_invite_works_hours, hours, hours)
            else -> stringResource(R.string.groups_invite_works_soon)
        }
    return stringResource(R.string.groups_invite_strip_label, works)
}

/** "Invite code · Expired 2 days ago", in the platform's own relative wording. */
@Composable
private fun expiredLabel(
    expiresAt: Instant,
    now: Instant,
): String {
    val ago =
        DateUtils
            .getRelativeTimeSpanString(expiresAt.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    return stringResource(R.string.groups_invite_strip_label, stringResource(R.string.groups_invite_strip_expired, ago))
}

/** The two tabs, each with its count; the watchlist count only once every page has loaded. */
@Composable
internal fun GroupTabs(
    selected: GroupTab,
    watchlistCount: Int?,
    memberCount: Int,
    onSelect: (GroupTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) {
        Tab(
            selected = selected == GroupTab.WATCHLIST,
            onClick = { onSelect(GroupTab.WATCHLIST) },
            text = {
                Text(
                    text =
                        watchlistCount?.let { stringResource(R.string.groups_tab_watchlist_count, it) }
                            ?: stringResource(R.string.groups_tab_watchlist),
                )
            },
        )
        Tab(
            selected = selected == GroupTab.MEMBERS,
            onClick = { onSelect(GroupTab.MEMBERS) },
            text = { Text(text = stringResource(R.string.groups_tab_members_count, memberCount)) },
        )
    }
}

internal enum class GroupTab { WATCHLIST, MEMBERS }
