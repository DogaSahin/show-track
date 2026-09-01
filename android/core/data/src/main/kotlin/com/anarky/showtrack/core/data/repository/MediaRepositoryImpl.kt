package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.paging.NumberedPage
import com.anarky.showtrack.core.data.paging.PagePaginator
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.network.api.ShowTrackApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search is NOT cached in Room. It is transient, it writes nothing, and its results have no id to
 * key a cache on (decision C-N). Room stays the library's render cache and nothing else.
 *
 * [PagePaginator], not [com.anarky.showtrack.core.data.paging.CursorPaginator]: `/v1/media/search`
 * is the API's one page-based endpoint, because it merges two independently-paginated upstreams
 * and so has no stable total order to cursor over.
 */
@Singleton
class MediaRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
    ) : MediaRepository {
        private val mutableResults = MutableStateFlow(SearchResults.EMPTY)
        override val searchResults: StateFlow<SearchResults> = mutableResults.asStateFlow()

        private var query: String = ""
        private var latest: SearchResults = SearchResults.EMPTY

        // ONE paginator for the life of the repository, whose fetch reads `query` when it runs.
        // `restart()` resets the page counter, so a new query needs no new instance — and a
        // stable instance keeps `paginator.items` a single flow that callers can hold.
        private val paginator =
            PagePaginator { page ->
                val response = api.searchMedia(query = query, page = page)
                latest = response.toDomain()
                NumberedPage(items = latest.items, hasMore = response.hasMore)
            }

        override suspend fun search(query: String) {
            this.query = query
            paginator.restart()
            publish()
        }

        override suspend fun loadMoreResults() {
            paginator.loadMore()
            publish()
        }

        override suspend fun detail(mediaId: String): Media = api.mediaDetail(mediaId).toDomain()

        private fun publish() {
            mutableResults.value = latest.copy(items = paginator.items.value)
        }
    }
