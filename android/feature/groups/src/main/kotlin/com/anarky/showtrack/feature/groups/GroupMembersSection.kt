package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.GroupMember

/**
 * The member list's own rendering — pulled out of `GroupDetailScreen.kt` (task 9c.3) purely to keep
 * that file under detekt's `TooManyFunctions` threshold, `GroupWatchlistSection.kt`'s own precedent
 * for the identical split.
 *
 * **Emits into an ENCLOSING [LazyListScope] rather than owning a `Column`/`LazyColumn` of its own**
 * (round 1 fix of this task): an EARLIER version of this file rendered a plain, non-scrolling
 * `Column`, on the theory that `GET /members` is unpaginated and a closed group's membership is
 * small — but sharing a `Column` with the watchlist's OWN weighted `LazyColumn` starved the
 * watchlist of layout height under `GroupDetailScreenTest`'s Robolectric viewport (measured: a
 * second watchlist row existed in composition but never got measured). Folding both sections into
 * ONE `LazyColumn` (`GroupDetailSuccessContent`, `GroupDetailScreen.kt`) removes the height
 * competition entirely — `GroupWatchlistSection.kt`'s own KDoc has the fuller account.
 */
internal fun LazyListScope.membersItems(
    members: List<GroupMember>,
    currentUserId: String?,
    isOwner: Boolean,
    onRemoveClick: (GroupMember) -> Unit,
) {
    // Keyed by userId, GroupsList's identical reasoning: without a key a reorder from a refresh
    // re-uses the wrong composable state for the wrong row.
    items(items = members, key = { "member:${it.userId}" }) { member ->
        MemberRow(
            member = member,
            // Owner-only (E-F), and NEVER for the viewer's own row — design doc §1.1: removing
            // yourself is "Leave group", not this control reused with your own id.
            showRemove = isOwner && member.userId != currentUserId,
            onRemoveClick = { onRemoveClick(member) },
        )
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    showRemove: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = member.username, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(member.role.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showRemove) {
                TextButton(onClick = onRemoveClick) {
                    Text(text = stringResource(R.string.groups_detail_remove_action))
                }
            }
        }
    }
}
