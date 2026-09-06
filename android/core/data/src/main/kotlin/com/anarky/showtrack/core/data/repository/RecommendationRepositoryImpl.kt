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
        // `restart()` drops the cursor; there is no filter/query field on this repository whose
        // agreement with the paginator [applyFilter]-style code elsewhere has to preserve across a
        // throw — recommendations take no client-chosen parameter to go stale.
        private val paginator =
            CursorPaginator<Recommendation> { cursor ->
                val response = api.recommendations(cursor = cursor, limit = PAGE_SIZE)
                Page(response.items.map(RecommendationDto::toDomain), response.nextCursor)
            }

        // A SEPARATE published list from `paginator.items`, deliberately — not merely a
        // `paginator.items` passthrough the way `MediaRepositoryImpl.searchResults` almost is.
        // `remove()` below mutates ONLY this field; `paginator.items` (CursorPaginator's own
        // internal accumulation) is never touched by it and keeps growing untouched underneath.
        // If `feed` instead re-published straight from `paginator.items.value` on every
        // `loadMore()` (the way `MediaRepositoryImpl.publish()` does), a later `loadMore()` would
        // silently RESURRECT whatever `remove()` had just taken off screen, because the paginator
        // itself never learned the row was gone. Appending only the page `loadMore()` RETURNED onto
        // whatever `mutableFeed` currently holds is what keeps a removal permanent across further
        // paging.
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
         * Appends the page `loadMore()` actually fetched onto [mutableFeed] — never re-publishes
         * the whole of `paginator.items.value`, see [mutableFeed]'s KDoc for why. A throwing fetch
         * never reaches the assignment below, so [mutableFeed] is left exactly as it was, the same
         * fetch-before-mutate property [refresh] relies on.
         *
         * **Whole-branch fix round, BLOCKING 4** — `LibraryRepositoryImpl.loadMoreFavorites`'s
         * identical shape, fixed together because it is one defect in two places. This used to read
         * `paginator.hasMore.value` BEFORE `loadMore()` suspended on the paginator's mutex, then
         * append a `lastFetchedPage` field read AFTER it returned; a concurrent [refresh] coming
         * back exhausted in between left this appending the refresh's own page a second time, for
         * duplicate ids in a `LazyColumn` keyed by `media.id`. `loadMore()` now decides inside the
         * lock and answers `null` when it fetched nothing.
         */
        override suspend fun loadMore() {
            val page = paginator.loadMore() ?: return
            mutableFeed.value = mutableFeed.value + page
        }

        override fun remove(mediaId: String) {
            mutableFeed.value = mutableFeed.value.filterNot { it.media.id == mediaId }
        }

        /**
         * `coerceIn(0, size)`, not a blind insert at [index]. This clamp is **load-bearing**, not
         * defensive — corrected in task 9c.8 round 5, having been described as the opposite since
         * it was written.
         *
         * The original reasoning covered one race and concluded the clamp was precautionary: a
         * `loadMore()` completing while the optimistic add was in flight can only have GROWN
         * [mutableFeed], by appending past the removal point, so [index] stayed valid. That is
         * still true of `loadMore()`. It is no longer the only thing that can run during an add:
         * since task 9c.8 round 3, `DiscoverViewModel.add` is deliberately not blocked by an
         * in-flight `refresh()`, and [refresh] TRUNCATES the feed to page 1. So a row added from
         * page 3 at index 45, whose add then fails, can reach this function with a 20-element
         * [mutableFeed] — and `MutableList.add(45, e)` throws [IndexOutOfBoundsException]. The
         * clamp is the only thing standing between that sequence and a crash.
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
