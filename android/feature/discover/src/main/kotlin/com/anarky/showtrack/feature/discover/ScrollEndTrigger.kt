package com.anarky.showtrack.feature.discover

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

internal const val DISCOVER_LIST_TAG = "discover_list"

internal fun discoverShelfTag(seedMediaId: String) = "discover_shelf_$seedMediaId"

/**
 * Calls [onTriggered] once each time a scroll of [state] FINISHES with its last visible item
 * within [threshold] of the end. Not on first composition, not when items arrive, not on resume:
 * only a scroll can ask for more.
 *
 * Discover needs this rather than the shared end-of-list trigger, which fires whenever the end is
 * visible. With a handful of shelves the end is visible on open, and pages land inside shelves
 * that already exist, so that trigger re-armed after every page and paged to the end of the feed.
 */
@Composable
internal fun ScrollEndTrigger(
    state: LazyListState,
    threshold: Int,
    onTriggered: () -> Unit,
) {
    val latest by rememberUpdatedState(onTriggered)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1) // the initial "not scrolling"
            .filter { scrolling -> !scrolling }
            .collect {
                val info = state.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                if (info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - threshold) latest()
            }
    }
}
