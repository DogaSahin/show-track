package com.anarky.showtrack.core.data.paging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of a page-numbered endpoint: `{items, page, has_more}`. */
data class NumberedPage<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

/**
 * [CursorPaginator]'s shape for `/v1/media/search`, the documented exception to architecture
 * rule 4: that endpoint merges two independently-paginated upstreams, so it can offer a page
 * number and a `has_more` flag but not a stable cursor.
 *
 * Separate from [CursorPaginator] rather than a parameterised one: the two endpoints differ in
 * kind, not in configuration, and a single class taking "either a cursor or a page number" would
 * carry a nullable of each and a branch on every call.
 *
 * Note what is missing compared to [CursorPaginator] — the `started` flag. It is not needed here
 * because the page number is never ambiguous: page 1 is a real starting value and `hasMore`
 * begins `true`, so "not begun" and "finished" are already distinct states.
 */
class PagePaginator<T>(
    private val fetch: suspend (page: Int) -> NumberedPage<T>,
) {
    private val mutex = Mutex()
    private var nextPage = FIRST_PAGE

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    suspend fun loadMore() {
        mutex.withLock {
            if (!_hasMore.value) return
            val page = fetch(nextPage)
            nextPage += 1
            _hasMore.value = page.hasMore
            _items.value = _items.value + page.items
        }
    }

    /** [CursorPaginator.restart]'s contract, for the same two reasons — see its doc comment. */
    suspend fun restart(): List<T> =
        mutex.withLock {
            val page = fetch(FIRST_PAGE)
            nextPage = FIRST_PAGE + 1
            _hasMore.value = page.hasMore
            _items.value = page.items
            page.items
        }

    private companion object {
        // The backend's search pagination is 1-based, not 0-based.
        const val FIRST_PAGE = 1
    }
}
