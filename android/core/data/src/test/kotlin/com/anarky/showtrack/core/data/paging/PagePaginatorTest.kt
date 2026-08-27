package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
