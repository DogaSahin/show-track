package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
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
 * Used by the lists that page on scroll (Feed, Discover, Favourites, group detail). Library does
 * not: its Home is a sectioned grid that expands in place ("Show all N"), and a single-status view
 * pages through [EndOfGridTrigger]. Call sites that do not pass [threshold] get
 * [DEFAULT_THRESHOLD], i.e. the next page is asked for when the last visible item is within three
 * of the end.
 *
 * **[rearmKey] exists because [itemCount] is not always what changes when a page lands.** Every
 * flat list here indexes one row per item, so a new page grows [itemCount] and the
 * `remember(itemCount)` below re-evaluates. `:feature:discover` breaks that assumption: it groups
 * recommendations into per-seed shelves, so its index space is SHELVES while a page arrives as
 * ITEMS — and because the API orders recommendations by score rather than by seed, a page very
 * often lands entirely inside shelves that already exist. [itemCount] is then unchanged, the cached
 * `derivedStateOf` keeps reporting `true`, `LaunchedEffect` sees no false→true edge, and paging
 * stops dead with a live cursor still in hand. Passing the flat item count as [rearmKey] re-arms
 * the trigger on the thing that actually moved. Defaults to [itemCount], so every existing call
 * site behaves exactly as before.
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
    rearmKey: Any = itemCount,
    onTriggered: () -> Unit,
) {
    val shouldTrigger by
        remember(itemCount, rearmKey) {
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

/**
 * [EndOfListTrigger] for a lazy grid: the same edge-triggered "near the end" signal, read from a
 * [LazyGridState]. [threshold] counts items, not rows, so a three-column grid asks for the next
 * page about one row before its end.
 */
@Composable
fun EndOfGridTrigger(
    gridState: LazyGridState,
    itemCount: Int,
    threshold: Int = DEFAULT_THRESHOLD,
    rearmKey: Any = itemCount,
    onTriggered: () -> Unit,
) {
    val shouldTrigger by
        remember(itemCount, rearmKey) {
            derivedStateOf {
                val lastVisibleIndex =
                    gridState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                itemCount > 0 && lastVisibleIndex >= itemCount - threshold
            }
        }
    LaunchedEffect(shouldTrigger) {
        if (shouldTrigger) onTriggered()
    }
}
