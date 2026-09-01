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
     * `restart()` is what makes `refresh()` a refresh rather than a continuation. Without it the
     * second pass would resume at `c1` and the caller would cache page 2 as if it were page 1.
     */
    @Test
    fun `restart goes back to the first page and returns it`() =
        runTest {
            val requested = mutableListOf<String?>()
            val paginator =
                CursorPaginator<String> { cursor ->
                    requested += cursor
                    twoPages.getValue(cursor)
                }

            paginator.loadMore()
            paginator.loadMore()
            val page = paginator.restart()

            assertEquals(listOf(null, "c1", null), requested)
            assertEquals(listOf("a"), page)
            assertEquals(listOf("a"), paginator.items.value)
            assertTrue(paginator.hasMore.value)
        }

    /**
     * The reason `restart()` is ONE method rather than a `reset()` the caller follows with
     * `loadMore()`: that sequence takes the lock TWICE, and a scroll-triggered `loadMore()` can
     * land in the gap, fetch page 1 itself and leave the restart fetching page 2 — after which
     * the restart returns two pages and the caller caches both while believing it cached one.
     *
     * The three-coroutine shape is not decoration, and getting it wrong is how this test can pass
     * against the very bug it names. A `loadMore()` must already be **parked on the mutex** when
     * the restart begins. Launch the restart first and it never yields: `mutex.withLock` on an
     * uncontended mutex does not suspend, so a two-lock restart runs its reset AND enters its
     * load before any other coroutine starts, and there is no gap to slip into. So: put a fetch
     * in flight holding the lock, queue the restart behind it, queue a scroll behind that.
     * kotlinx's `Mutex` is FIFO, so the scroll is guaranteed the lock the moment a two-lock
     * restart releases it between its two halves. Verified by mutation — the earlier two-
     * coroutine version of this test passed against that mutant.
     *
     * `runTest` proves the lock is HELD across the whole operation; it cannot prove anything
     * about true parallelism, which is a different claim needing a different tool.
     */
    @Test
    fun `a restart and a concurrent loadMore cannot interleave`() =
        runTest {
            val paginator =
                CursorPaginator<String> { cursor ->
                    delay(10) // widen the window the mutex has to close
                    threePages.getValue(cursor)
                }

            val inFlight = async { paginator.loadMore() } // takes the lock and holds it
            val restarted = async { paginator.restart() } // parks on the mutex
            val scrolled = async { paginator.loadMore() } // parks behind the restart
            val page = restarted.await()
            inFlight.await()
            scrolled.await()

            // The restart's OWN page, never one a concurrent loadMore appended underneath it.
            assertEquals(listOf("a"), page)
        }

    /**
     * A refresh is never destructive. `restart()` fetches before it mutates, so a failure leaves
     * the loaded pages, the cursor and the exhaustion state exactly as they were — the user keeps
     * the rows they were looking at instead of watching the list collapse. The follow-up
     * `loadMore()` is what proves the CURSOR survived too, not just the visible items: the
     * paginator is still exhausted, so it must stay a no-op.
     */
    @Test
    fun `a failed restart leaves the loaded pages and the cursor untouched`() =
        runTest {
            var failing = false
            val paginator =
                CursorPaginator<String> { cursor ->
                    if (failing) throw IOException("simulated network failure")
                    twoPages.getValue(cursor)
                }
            paginator.loadMore()
            paginator.loadMore()

            failing = true
            runCatching { paginator.restart() }

            assertEquals(listOf("a", "b"), paginator.items.value)
            assertFalse(paginator.hasMore.value)

            failing = false
            paginator.loadMore()
            assertEquals(listOf("a", "b"), paginator.items.value)
        }

    private val threePages =
        mapOf(
            null to Page(listOf("a"), "c1"),
            "c1" to Page(listOf("b"), "c2"),
            "c2" to Page(listOf("c"), null),
        )

    private val twoPages =
        mapOf(
            null to Page(listOf("a"), "c1"),
            "c1" to Page(listOf("b"), null),
        )
}
