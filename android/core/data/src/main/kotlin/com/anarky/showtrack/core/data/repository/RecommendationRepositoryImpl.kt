package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.paging.CursorPaginator
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.RecommendationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20

/**
 * `@Singleton` on the class, not on `DataModule`'s `@Binds` method — [LibraryRepositoryImpl]'s
 * KDoc explains why that split matters: the state (the paginator, the published feed) belongs to
 * the type, and an unscoped binding would hand out a second instance — with its own cursor and its
 * own accumulated pages — to anyone injecting the concrete type.
 */
@Singleton
class RecommendationRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
    ) : RecommendationRepository {
        // The most recently FETCHED page's items — not the accumulated list. Captured inside the
        // fetch lambda the same way MediaRepositoryImpl.latest is: CursorPaginator.loadMore()
        // itself returns Unit, so this is the only way to learn what a successful loadMore() just
        // added, as opposed to re-reading `paginator.items.value` (see [mutableFeed]'s KDoc for
        // why that second option is wrong here).
        private var lastFetchedPage: List<Recommendation> = emptyList()

        // `restart()` drops the cursor; there is no filter/query field on this repository whose
        // agreement with the paginator [applyFilter]-style code elsewhere has to preserve across a
        // throw — recommendations take no client-chosen parameter to go stale.
        private val paginator =
            CursorPaginator<Recommendation> { cursor ->
                val response = api.recommendations(cursor = cursor, limit = PAGE_SIZE)
                val items = response.items.map(RecommendationDto::toDomain)
                lastFetchedPage = items
                Page(items, response.nextCursor)
            }

        // A SEPARATE published list from `paginator.items`, deliberately — not merely a
        // `paginator.items` passthrough the way `MediaRepositoryImpl.searchResults` almost is.
        // `remove()` below mutates ONLY this field; `paginator.items` (CursorPaginator's own
        // internal accumulation) is never touched by it and keeps growing untouched underneath.
        // If `feed` instead re-published straight from `paginator.items.value` on every
        // `loadMore()` (the way `MediaRepositoryImpl.publish()` does), a later `loadMore()` would
        // silently RESURRECT whatever `remove()` had just taken off screen, because the paginator
        // itself never learned the row was gone. Appending only `lastFetchedPage` onto whatever
        // `mutableFeed` currently holds is what keeps a removal permanent across further paging.
        private val mutableFeed = MutableStateFlow<List<Recommendation>>(emptyList())
        override val feed: StateFlow<List<Recommendation>> = mutableFeed.asStateFlow()

        /**
         * `paginator.restart()`'s RETURNED page, not a re-read of [lastFetchedPage] or
         * `paginator.items.value` — though either would agree here. `CursorPaginator.restart`
         * fetches before it mutates anything, so if the fetch throws this line never runs and
         * [mutableFeed] is left exactly as it was: a failed refresh leaves the feed the user is
         * looking at standing, rather than blanking it (the same "fetch before mutate" shape
         * `LibraryRepositoryImpl.refresh`/`MediaRepositoryImpl.search` rely on — carried forward
         * per the task brief, this exact shape has produced two bugs in this project already when
         * a caller re-derived its own state from something a throw could leave stale instead of
         * from the fetch's own result).
         */
        override suspend fun refresh() {
            val firstPage = paginator.restart()
            mutableFeed.value = firstPage
        }

        /**
         * Appends [lastFetchedPage] onto [mutableFeed] — never re-publishes the whole of
         * `paginator.items.value` — see [mutableFeed]'s KDoc for why. `paginator.loadMore()`
         * throwing leaves [lastFetchedPage] (still the PREVIOUS page) and therefore [mutableFeed]
         * exactly as they were, for the same fetch-before-mutate reason [refresh] relies on: the
         * assignment below is never reached when the fetch fails.
         *
         * The `paginator.hasMore.value` guard below is NOT redundant with `CursorPaginator`'s own
         * `started && cursor == null` no-op check: that guard makes `paginator.loadMore()` a
         * harmless no-op for a caller reading `paginator.items` directly, but [lastFetchedPage]
         * here is a field that OUTLIVES a single call — without this check, an exhausted
         * `paginator.loadMore()` fetches nothing and this function would still append the STALE
         * [lastFetchedPage] from the last call that actually fetched, duplicating the final page
         * onto the feed every time a `LazyColumn` sitting at the bottom fires `loadMore()` again.
         */
        override suspend fun loadMore() {
            if (!paginator.hasMore.value) return
            paginator.loadMore()
            mutableFeed.value = mutableFeed.value + lastFetchedPage
        }

        override fun remove(mediaId: String) {
            mutableFeed.value = mutableFeed.value.filterNot { it.media.id == mediaId }
        }

        /**
         * `coerceIn(0, size)`, not a blind insert at [index]: a `loadMore()` that completed while
         * the row was out (D-I's optimistic add is in flight) can only have grown [mutableFeed] by
         * appending past the removal point — `loadMore` never inserts before it — so [index] is
         * always still a valid position; the clamp is defensive against a future change to that
         * assumption, not load-bearing for the race as it exists today.
         */
        override fun restore(
            index: Int,
            recommendation: Recommendation,
        ) {
            mutableFeed.value =
                mutableFeed.value.toMutableList().apply {
                    add(index.coerceIn(0, size), recommendation)
                }
        }
    }
