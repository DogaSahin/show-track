package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * The half of [PagePaginator.restart]'s contract that is easy to get wrong, mirrored from
     * [CursorPaginator]: it fetches BEFORE it mutates, so a failed refresh is not destructive —
     * the loaded pages and the page counter survive. Only this property is tested here rather
     * than the whole of `restart`; the rest is a direct mirror covered by `CursorPaginatorTest`.
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
