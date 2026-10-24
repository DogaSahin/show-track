package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.UserAvatar
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.WatchlistEntry

private const val GRID_COLUMNS = 3
private val SuggesterSize = 22.dp

/**
 * The Watchlist tab: a 3-column poster grid, newest first, paged as before. Laid out as rows of
 * three inside the page's one `LazyColumn`, so the header scrolls away, the tabs stick, and paging
 * keeps counting rows the list actually lays out.
 *
 * Tap a poster to open it; long-press for Remove from watchlist (any member may, as the backend
 * allows), which opens the existing confirmation dialog.
 *
 * Each poster carries its suggester's avatar. TalkBack reads it as "Proposed by sam", or, when the
 * proposer has left or deleted their account, the same "not someone currently here" fallback as
 * before: the viewer cannot tell those two apart, so one honest string covers both.
 */
@Suppress("LongParameterList")
internal fun LazyListScope.watchlistItems(
    entries: List<WatchlistEntry>,
    members: List<GroupMember>,
    loadingMore: Boolean,
    pageError: Boolean,
    isStale: Boolean,
    onLoadMore: () -> Unit,
    onRemoveClick: (WatchlistEntry) -> Unit,
    onEntryClick: (WatchlistEntry) -> Unit,
) {
    // Extracted to a named val, not inlined into the `if` — detekt's ComplexCondition threshold
    // (4 boolean operators) trips on the four terms combined directly.
    val nothingToShow = entries.isEmpty() && !loadingMore && !pageError && !isStale
    if (nothingToShow) {
        item(key = "watchlist-empty") { WatchlistEmpty() }
        return
    }

    // Keyed by the row's first entry id: a reload that reorders the list re-keys the rows rather
    // than reusing one row's state for another's titles.
    itemsIndexed(items = entries.chunked(GRID_COLUMNS), key = {
        _,
        row,
        ->
        "watchlist-row:${row.first().id}"
    }) { _, row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(space = 9.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            row.forEach { entry ->
                WatchlistPoster(
                    entry = entry,
                    proposer = members.firstOrNull { it.userId == entry.proposedBy },
                    onClick = { onEntryClick(entry) },
                    onRemoveClick = { onRemoveClick(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(GRID_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
    if (loadingMore) {
        item(key = "watchlist-loading-more") {
            LoadingState(modifier = Modifier.testTag("watchlist-loading-more"))
        }
    } else if (pageError) {
        item(key = "watchlist-page-error") {
            Text(
                text = stringResource(R.string.groups_watchlist_page_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .testTag("watchlist-page-error")
                        .fillMaxWidth()
                        .clickable(onClick = onLoadMore)
                        .padding(all = 12.dp),
            )
        }
    }
}

@Composable
private fun WatchlistEmpty() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(space = 6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.groups_watchlist_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.groups_watchlist_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchlistPoster(
    entry: WatchlistEntry,
    proposer: GroupMember?,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val proposedBy =
        proposer?.let { stringResource(R.string.groups_watchlist_proposed_by, it.username) }
            ?: stringResource(R.string.groups_watchlist_no_proposer)
    val ring = MaterialTheme.colorScheme.background
    Box(modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 5.dp),
            modifier =
                Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.groups_watchlist_remove_action),
                ),
        ) {
            Box(contentAlignment = Alignment.BottomStart) {
                MediaCover(coverImageUrl = entry.media.coverImageUrl, modifier = Modifier.fillMaxWidth())
                Box(
                    modifier =
                        Modifier
                            .padding(all = 4.dp)
                            .border(width = 2.dp, color = ring, shape = CircleShape)
                            .semantics { contentDescription = proposedBy },
                ) {
                    proposer?.let { UserAvatar(userId = it.userId, name = it.username, size = SuggesterSize) }
                }
            }
            Text(
                text = entry.media.title,
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.groups_watchlist_remove_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onRemoveClick()
                },
                modifier = Modifier.testTag("watchlist-remove-${entry.id}"),
            )
        }
    }
}
