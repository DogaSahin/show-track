package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CursorPaginatorTest {
    @Test
    fun `it walks every page exactly once and stops on a null cursor`() =
        runTest {
            val pages =
                mapOf(
                    null to Page(listOf("a", "b"), "c1"),
                    "c1" to Page(listOf("c", "d"), "c2"),
                    "c2" to Page(listOf("e"), null),
                )
            val paginator = CursorPaginator<String> { cursor -> pages.getValue(cursor) }

            paginator.loadMore()
            paginator.loadMore()
            paginator.loadMore()

            assertEquals(listOf("a", "b", "c", "d", "e"), paginator.items.value)
            assertFalse(paginator.hasMore.value)
        }

    @Test
    fun `loadMore after exhaustion is a no-op, not a repeat of the last page`() =
        runTest {
            // The trap: a paginator that forgets it finished re-requests cursor=null and silently
            // duplicates page 1 at the bottom of the list.
            val single = CursorPaginator<String> { Page(listOf("only"), null) }
            single.loadMore()

            val before = single.items.value
            single.loadMore()

            assertEquals(before, single.items.value)
        }

    @Test
    fun `a concurrent loadMore does not fetch the same cursor twice`() =
        runTest {
            val pages =
                mapOf(
                    null to Page(listOf("a"), "c1"),
                    "c1" to Page(listOf("b"), null),
                )
            val paginator =
                CursorPaginator<String> { cursor ->
                    delay(10) // widen the window the mutex has to close
                    pages.getValue(cursor)
                }

            listOf(async { paginator.loadMore() }, async { paginator.loadMore() }).awaitAll()

            assertEquals(listOf("a", "b"), paginator.items.value)
        }

    /**
     * `reset()` is what makes `refresh()` a refresh rather than a continuation. Without it the
     * second pass would resume at `c1` and the caller would cache page 2 as if it were page 1.
     */
    @Test
    fun `reset sends the next load back to the first page`() =
        runTest {
            val requested = mutableListOf<String?>()
            val pages =
                mapOf(
                    null to Page(listOf("a"), "c1"),
                    "c1" to Page(listOf("b"), null),
                )
            val paginator =
                CursorPaginator<String> { cursor ->
                    requested += cursor
                    pages.getValue(cursor)
                }

            paginator.loadMore()
            paginator.loadMore()
            paginator.reset()
            paginator.loadMore()

            assertEquals(listOf(null, "c1", null), requested)
            assertEquals(listOf("a"), paginator.items.value)
            assertEquals(true, paginator.hasMore.value)
        }
}
