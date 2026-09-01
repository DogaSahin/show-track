package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of a cursor-paginated endpoint. A null [nextCursor] means this was the last page. */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/**
 * Hand-rolled pagination over the `{items, next_cursor}` envelope of architecture rule 4
 * (decision A-G). Paging 3 was the alternative and is better at windowing, placeholders and
 * prefetch — but its offline story is `RemoteMediator`, which makes Room the paging source of
 * truth and hands back the exact rule the build enforces in `ModuleRules`.
 *
 * State lives on the instance, so a [CursorPaginator] must be held for as long as the list it
 * pages is on screen — in practice, inside a `@Singleton` repository.
 */
class CursorPaginator<T>(
    private val fetch: suspend (cursor: String?) -> Page<T>,
) {
    private val mutex = Mutex()
    private var cursor: String? = null

    /**
     * Not redundant with `cursor == null`, which means two different things: "not begun" and
     * "finished". Conflate them and an exhausted paginator re-requests the first page and
     * silently duplicates it at the bottom of the list — a bug that looks like a backend fault.
     */
    private var started = false

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /**
     * The mutex, not an `isLoading` boolean: a scroll listener firing twice before the first
     * response lands would otherwise send both requests with the same cursor and append the same
     * page twice. Checking a flag is not atomic across a suspension point; taking a lock is.
     */
    suspend fun loadMore() {
        mutex.withLock {
            if (started && cursor == null) return // exhausted; NOT a reason to restart
            val page = fetch(cursor)
            started = true
            cursor = page.nextCursor
            _hasMore.value = page.nextCursor != null
            _items.value = _items.value + page.items
        }
    }

    /**
     * Reload from the first page and return the page that was loaded. Two properties, both of
     * which are the reason this exists instead of a `reset()` the caller follows with
     * [loadMore] — that sequence takes the lock TWICE, and a scroll-triggered [loadMore] slipping
     * into the gap is the ordinary case for a pull-to-refresh, not an exotic one.
     *
     * **One lock across the whole operation.** With two acquisitions, a concurrent [loadMore] can
     * fetch the first page in the gap, leaving this call to fetch the SECOND — after which the
     * caller's "first page only" snapshot silently holds two pages.
     *
     * **The fetch happens BEFORE anything is mutated.** So a failed refresh leaves the cursor,
     * the exhaustion state and the loaded pages exactly as they were: the user keeps the rows
     * they were looking at instead of watching the list collapse to a stale cached page and then
     * re-expand. Clearing first would also make the failure destructive, which a refresh never
     * should be.
     *
     * Returning the page rather than leaving the caller to re-read [items] closes the same race
     * one step further out: [items] can have grown by the time the caller looks at it.
     */
    suspend fun restart(): List<T> =
        mutex.withLock {
            val page = fetch(null)
            cursor = page.nextCursor
            started = true
            _hasMore.value = page.nextCursor != null
            _items.value = page.items
            page.items
        }
}
