package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

private const val DEFAULT_THRESHOLD = 3

/**
 * Fires [onTriggered] once when the list scrolls within [threshold] items of its end.
 *
 * `remember(itemCount)` is load-bearing, not decoration: without the key the `derivedStateOf`
 * captures the FIRST item count forever and the trigger never fires again after page one. And
 * `LaunchedEffect` keyed on the boolean fires only on the false→true transition, which is why the
 * caller still needs its own re-entrancy guard for the in-flight case — a `LazyColumn` near its end
 * recomposes on every frame, but this only *emits* on the edge.
 *
 * **This changed `:feature:library`'s own firing point, not merely its implementation.** Before
 * this extraction, `LibraryList`'s inline condition fired only once the LAST row itself was
 * visible (`lastVisibleIndex >= entries.lastIndex`, equivalent to `threshold = 0`). Every call site
 * that does not pass [threshold] explicitly — `LibraryList` included — now gets [DEFAULT_THRESHOLD]
 * (3), so Library prefetches three rows earlier than it used to. That is the shape the task brief
 * specified verbatim and is benign (`LibraryViewModel.loadMore`'s re-entrancy guard and
 * `CursorPaginator`'s own exhaustion check both still hold at the new firing point), but it is a
 * real behaviour change, not a like-for-like refactor — no `:feature:library` test exercised the
 * composable's scroll trigger before or after, so nothing would have gone red either way.
 *
 * `itemCount: Int` rather than the list itself (decision D-H, extracted from `:feature:library`):
 * the primitive key is behaviourally equivalent to keying on the list reference for THIS
 * calculation specifically, because the calculation only ever reads `itemCount` and
 * `listState.layoutInfo` — never an actual element. A `remember(itemCount)` that reuses a cached
 * `derivedStateOf` because a filter swap happened to yield the same row COUNT still reuses a
 * closure that captured the identical `Int` value the swap would have produced anyway, so there is
 * no stale-closure gap to reopen here the way there would be if this ever compared element
 * *content* (e.g. "is the last visible row's id equal to X") — at that point the list reference
 * would become load-bearing again and this key would need to change with it.
 */
@Composable
fun EndOfListTrigger(
    listState: LazyListState,
    itemCount: Int,
    threshold: Int = DEFAULT_THRESHOLD,
    onTriggered: () -> Unit,
) {
    val shouldTrigger by
        remember(itemCount) {
            derivedStateOf {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: -1
                itemCount > 0 && lastVisibleIndex >= itemCount - threshold
            }
        }
    LaunchedEffect(shouldTrigger) {
        if (shouldTrigger) onTriggered()
    }
}
