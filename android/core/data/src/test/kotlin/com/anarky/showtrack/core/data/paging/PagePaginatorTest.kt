package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PagePaginatorTest {
    @Test
    fun `it walks the pages in order and stops when has_more goes false`() =
        runTest {
            val requested = mutableListOf<Int>()
            val paginator =
                PagePaginator<String> { page ->
                    requested += page
                    when (page) {
                        1 -> NumberedPage(listOf("a"), hasMore = true)
                        else -> NumberedPage(listOf("b"), hasMore = false)
                    }
                }

            paginator.loadMore()
            paginator.loadMore()
            paginator.loadMore()

            assertEquals(listOf(1, 2), requested)
            assertEquals(listOf("a", "b"), paginator.items.value)
            assertFalse(paginator.hasMore.value)
        }

    @Test
    fun `a concurrent loadMore does not fetch the same page twice`() =
        runTest {
            val paginator =
                PagePaginator<Int> { page ->
                    delay(10)
                    NumberedPage(listOf(page), hasMore = page < 2)
                }

            listOf(async { paginator.loadMore() }, async { paginator.loadMore() }).awaitAll()

            assertEquals(listOf(1, 2), paginator.items.value)
        }

    /**
     * [PagePaginator.restart]'s success path: it goes back to page 1, returns that page, and
     * leaves the counter pointing at page 2.
     *
     * This exists because the failure-path test below cannot see it. A `restart()` that fetched
     * correctly and then did `_items.value = emptyList(); emptyList()` — silently blanking the
     * list and returning nothing — passed every other test in this module. `CursorPaginatorTest`
     * does not cover it either: that is a different class, and "it is a direct mirror" is an
     * argument from inspection, not coverage.
     */
    @Test
    fun `restart goes back to the first page, returns it, and advances the counter`() =
        runTest {
            val requested = mutableListOf<Int>()
            val paginator =
                PagePaginator<String> { page ->
                    requested += page
                    NumberedPage(listOf("p$page"), hasMore = page < 3)
                }
            paginator.loadMore()
            paginator.loadMore()

            val page = paginator.restart()

            assertEquals(listOf("p1"), page)
            assertEquals(listOf("p1"), paginator.items.value)
            assertTrue(paginator.hasMore.value)

            // The counter advanced to 2, so the next load continues rather than repeating page 1.
            paginator.loadMore()
            assertEquals(listOf(1, 2, 1, 2), requested)
            assertEquals(listOf("p1", "p2"), paginator.items.value)
        }

    /**
     * The other half of the contract, and the one that is easy to get wrong: `restart()` fetches
     * BEFORE it mutates, so a failed refresh is not destructive — the loaded pages and the page
     * counter both survive.
     */
    @Test
    fun `a failed restart leaves the loaded pages and the page counter untouched`() =
        runTest {
            var failing = false
            val requested = mutableListOf<Int>()
            val paginator =
                PagePaginator<String> { page ->
                    requested += page
                    if (failing) throw IOException("simulated network failure")
                    NumberedPage(listOf("p$page"), hasMore = page < 2)
                }
            paginator.loadMore()

            failing = true
            runCatching { paginator.restart() }

            assertEquals(listOf("p1"), paginator.items.value)

            // The counter survived too: the next load is page 2, not a repeat of page 1.
            failing = false
            paginator.loadMore()
            assertEquals(listOf(1, 1, 2), requested)
            assertEquals(listOf("p1", "p2"), paginator.items.value)
        }
}
