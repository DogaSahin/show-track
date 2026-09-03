package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.WatchlistEntry

private val PosterWidth = 64.dp

/**
 * The shared watchlist section of `GroupDetailScreen` (task 9c.3, §3.4) — pulled out of
 * `GroupDetailScreen.kt` purely to keep that file under detekt's `TooManyFunctions` threshold,
 * `GroupDetailDialogs.kt`'s own precedent for the identical split.
 *
 * **Emits into an ENCLOSING [LazyListScope] rather than owning its own `LazyColumn`**: members and
 * watchlist rows share ONE scrollable `LazyColumn` (`GroupDetailList`, `GroupDetailScreen.kt`) —
 * `GroupMembersSection.kt`'s `membersItems` is the identical shape. This is a legitimate layout
 * simplification on its own merits (one continuous scroll region reads as one screen, not two
 * competing ones), independent of a SEPARATE, purely test-side issue it does not by itself fix:
 * `LazyColumn`'s own composition/prefetch window under Robolectric does not reliably reach a SECOND
 * poster-sized row on first layout the way it reaches several short text-only member rows —
 * `GroupDetailScreenTest`'s own tests scroll explicitly (`performScrollTo()`, or a `testTag` +
 * `performScrollToNode` for a row not yet composed at all) rather than assuming everything is
 * visible on the first frame, `ProfileScreenTest`'s `sign-out is reachable by scrolling...` own
 * precedent for the identical Robolectric characteristic.
 *
 * **Fix round 1 removed the propose action from this header.** A search-backed title picker turned
 * out to be impossible here, not merely expensive: `MediaSummary` has no id by design (decision
 * C-N — search writes nothing, so no row exists to have one), and the only place a persisted
 * `mediaId` exists is Detail, after a title has already been added to the proposer's own library.
 * "Propose to a group" moves to `:feature:detail` (task 9c.6), where a real `mediaId` already
 * exists. Shipping a raw-media-id text field here was worse than not offering the action yet.
 */
@Composable
internal fun WatchlistHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.groups_watchlist_section_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The watchlist's own rows, appended into [this] — the caller's shared `LazyColumn`. This
 * composable's own KDoc explains why it no longer owns a `LazyColumn`/`EndOfListTrigger` of its own;
 * [GroupDetailSuccessContent] (`GroupDetailScreen.kt`) is what drives [androidx.compose.foundation.lazy.LazyListState]
 * and [com.anarky.showtrack.core.designsystem.component.EndOfListTrigger] now, over the COMBINED
 * item count.
 *
 * Empty (no entries, nothing loading, no page error, AND not stale) emits [EmptyState] as a single
 * item instead of nothing at all — `LibraryScreen`'s identical branch for its own empty list,
 * adapted to a single `LazyListScope.item` rather than an early-return composable. [isStale] is
 * checked here too (fix round 1): [GroupDetailList] already renders a
 * `com.anarky.showtrack.core.designsystem.component.StaleDataBanner` above this section when
 * [GroupDetailUiState.Success.watchlistIsStale] is set — showing "No one
 * has proposed a title yet" UNDERNEATH a "couldn't refresh" banner would contradict it, since the
 * true reason the list looks empty may be that the last fetch never landed, not that it is
 * genuinely empty.
 *
 * [onEntryClick] (fix round 1) is what makes a row navigate to `DetailRoute(entry.mediaId)` —
 * `WatchlistEntry.mediaId`'s own KDoc names this as its reason for existing, and nothing consumed
 * it until now; `GroupsNavigation.kt`'s `groupDetailEntry` is where the actual `DetailRoute`
 * construction happens, this screen only hands the tapped [WatchlistEntry] upward.
 *
 * [members] is used ONLY to resolve [WatchlistEntry.proposedBy] (a raw user id on the wire,
 * `GroupMapper.WatchlistItemDto.toDomain`'s own KDoc — the backend's `proposed_by` column is a
 * `uuid.UUID`, not a username) against a display name — see [WatchlistEntryRow]'s own KDoc for why
 * a member who has since LEFT the group (still a valid id, no longer resolvable here) gets the
 * IDENTICAL fallback copy this task's brief asks for the null (deleted-account) case, rather than a
 * second, differently-worded "unknown" string for a cause the viewer cannot tell apart anyway.
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
    // (4 boolean operators) trips on the four terms combined directly; a named boolean reads just
    // as clearly and keeps the check itself a single-term condition.
    val nothingToShow = entries.isEmpty() && !loadingMore && !pageError && !isStale
    if (nothingToShow) {
        item(key = "watchlist-empty") {
            EmptyState(message = stringResource(R.string.groups_watchlist_empty_message))
        }
        return
    }

    // Keyed by entry id — LibraryList's identical reasoning: without a key, a reorder from a
    // reload re-uses the wrong composable state for the wrong row.
    items(items = entries, key = { "watchlist:${it.id}" }) { entry ->
        WatchlistEntryRow(
            entry = entry,
            proposerUsername = members.firstOrNull { it.userId == entry.proposedBy }?.username,
            onClick = { onEntryClick(entry) },
            onRemoveClick = { onRemoveClick(entry) },
        )
    }
    if (loadingMore) {
        item(key = "watchlist-loading-more") {
            LoadingState(modifier = Modifier.testTag("watchlist-loading-more"))
        }
    } else if (pageError) {
        // Not loading AND pageError == true: the previous page fetch already finished and
        // failed. Shown as a tap-to-retry row rather than a spinner — nothing is in flight for
        // the user to wait on (LibraryList's identical shape for its own footer error).
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

/**
 * One proposed title. Deliberately NOT `MediaCard` — that component takes a `Media`, while
 * [WatchlistEntry.media] is a [com.anarky.showtrack.core.model.MediaSummary], and the two are not
 * the same type (`SearchResultRow`'s identical note, `:feature:search` — `MediaCard` would not
 * compile against a [com.anarky.showtrack.core.model.MediaSummary]). Composes [MediaCover] plus text
 * instead, matching that screen's own substitute rather than inventing a third shape.
 *
 * **[onClick] (fix round 1) makes the whole card tappable → `DetailRoute(entry.mediaId)`**, a
 * SIBLING gesture to [onRemoveClick]'s own `TextButton` nested inside it — Compose's own pointer
 * input disambiguation is what keeps a tap on "Remove" from also firing the row's click; no extra
 * handling is needed here for that, the same nested-clickable shape `MediaCard`'s own `Card(onClick
 * = ...)` establishes for a library row.
 *
 * [proposerUsername] null renders [R.string.groups_watchlist_no_proposer], never a blank string —
 * this task's own first named test. It reads `null` for two different causes the viewer cannot
 * distinguish anyway ([WatchlistEntry.proposedBy] itself null — the account was deleted, design
 * §5.3 — or a non-null id [watchlistItems] could not resolve against the CURRENT member list,
 * because that member has since left the group): one fallback string covers both honestly, since
 * neither cause has anything more specific to say to this viewer than "not someone currently here".
 */
@Composable
private fun WatchlistEntryRow(
    entry: WatchlistEntry,
    proposerUsername: String?,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(all = 12.dp)) {
            MediaCover(coverImageUrl = entry.media.coverImageUrl, modifier = Modifier.width(PosterWidth))
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(weight = 1f),
                verticalArrangement = Arrangement.spacedBy(space = 4.dp),
            ) {
                Text(
                    text = entry.media.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        proposerUsername?.let { stringResource(R.string.groups_watchlist_proposed_by, it) }
                            ?: stringResource(R.string.groups_watchlist_no_proposer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemoveClick, modifier = Modifier.testTag("watchlist-remove-${entry.id}")) {
                Text(text = stringResource(R.string.groups_watchlist_remove_action))
            }
        }
    }
}
