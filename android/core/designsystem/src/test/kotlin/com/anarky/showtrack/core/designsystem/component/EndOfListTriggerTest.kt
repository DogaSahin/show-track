package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fix round 1, finding 3: this component shipped with no test at all — the brief's "its existing
 * tests must still pass" was satisfied only because `:feature:library` had none exercising the
 * scroll trigger before or after the extraction. This is now shared logic behind two features, and
 * its threshold semantics and once-per-edge firing are exactly what regresses silently.
 *
 * `createComposeRule` + `RobolectricTestRunner`, `StatusPresentationTest`'s own pattern —
 * `sdk = 35` comes from this module's `src/test/resources/robolectric.properties`.
 *
 * Scrolling is driven programmatically via `LazyListState.scrollToItem`, always to the LAST index
 * of whatever `itemCount` currently is: since it is the final item, no item beyond it can also be
 * "visible", so `lastVisibleIndex` after such a scroll is deterministically that index regardless
 * of viewport/row-height specifics — a `performScrollToIndex` gesture landing mid-list would not
 * give the same guarantee.
 */
@RunWith(RobolectricTestRunner::class)
class EndOfListTriggerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `does not fire while scrolled away from the end`() {
        var triggerCount = 0
        composeRule.setContent {
            val listState = rememberLazyListState()
            EndOfListTrigger(listState = listState, itemCount = 20, threshold = 3, onTriggered = { triggerCount++ })
            SimpleList(itemCount = 20, listState = listState)
        }
        composeRule.waitForIdle()

        assertEquals(0, triggerCount)
    }

    @Test
    fun `fires once scrolling within threshold of the end`() {
        var triggerCount = 0
        lateinit var scrollTarget: MutableState<Int?>
        composeRule.setContent {
            val listState = rememberLazyListState()
            scrollTarget = remember { mutableStateOf(null) }
            ScrollDriver(listState = listState, target = scrollTarget)
            EndOfListTrigger(listState = listState, itemCount = 20, threshold = 3, onTriggered = { triggerCount++ })
            SimpleList(itemCount = 20, listState = listState)
        }

        // Index 19 is the last row of 20 — well within threshold 3 of the end.
        scrollTarget.value = 19
        composeRule.waitForIdle()

        assertEquals(1, triggerCount)
    }

    /**
     * The property `remember(itemCount)`'s KDoc names directly: a stale closure that captured the
     * FIRST `itemCount` would never fire again once one page has loaded, because it can only
     * re-emit on a false→true transition and a stale closure evaluated against a since-grown list
     * stays stuck at whatever it last computed. This test fails against a `remember { }` with no
     * key (hand-verified before finalising this test): after growing `itemCount` without moving the
     * scroll position, the trigger must go quiet again — not stay fired from the first page — and
     * then fire a SECOND time once the list is scrolled to the NEW, further-out end.
     */
    @Test
    fun `fires again for a second page after itemCount grows, proving the remember key is not stale`() {
        var triggerCount = 0
        lateinit var itemCount: MutableState<Int>
        lateinit var scrollTarget: MutableState<Int?>
        composeRule.setContent {
            val listState = rememberLazyListState()
            itemCount = remember { mutableStateOf(10) }
            scrollTarget = remember { mutableStateOf(null) }
            ScrollDriver(listState = listState, target = scrollTarget)
            EndOfListTrigger(
                listState = listState,
                itemCount = itemCount.value,
                threshold = 3,
                onTriggered = { triggerCount++ },
            )
            SimpleList(itemCount = itemCount.value, listState = listState)
        }

        // First page's end: index 9 of 10.
        scrollTarget.value = 9
        composeRule.waitForIdle()
        assertEquals(1, triggerCount)

        // Page two loads: itemCount grows, scroll position untouched — now far from the NEW end
        // (30 - 3 = 27), so the trigger must go quiet rather than staying latched on the first page.
        itemCount.value = 30
        composeRule.waitForIdle()
        assertEquals("must not re-fire merely because itemCount changed", 1, triggerCount)

        // Scroll to the NEW end: index 29 of 30. A stale closure still comparing against the OLD
        // itemCount (10) would already read this as past-threshold and never re-emit — this is the
        // assertion that actually discriminates a correct remember(itemCount) key from a stale one.
        scrollTarget.value = 29
        composeRule.waitForIdle()
        assertEquals(2, triggerCount)
    }

    @Composable
    private fun ScrollDriver(
        listState: LazyListState,
        target: MutableState<Int?>,
    ) {
        LaunchedEffect(target.value) {
            target.value?.let { listState.scrollToItem(it) }
        }
    }

    @Composable
    private fun SimpleList(
        itemCount: Int,
        listState: LazyListState,
    ) {
        // A short viewport and tall rows: only a couple of rows are ever visible at once, so
        // scrolling to the list's last index is unambiguously what puts THAT index at the end of
        // the visible range.
        LazyColumn(state = listState, modifier = Modifier.height(120.dp)) {
            items(count = itemCount) { index -> Text(text = "Row $index", modifier = Modifier.height(80.dp)) }
        }
    }
}
