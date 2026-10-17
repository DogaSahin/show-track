package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.paging.NumberedPage
import com.anarky.showtrack.core.data.paging.PagePaginator
import com.anarky.showtrack.core.data.session.SessionGuard
import com.anarky.showtrack.core.data.session.UserData
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.ResolveMediaRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    ) : MediaRepository,
        UserData {
        // Results from a search still in flight at sign-out are dropped, never published.
        private val session = SessionGuard()

        private val mutableResults = MutableStateFlow(SearchResults.EMPTY)
        override val searchResults: StateFlow<SearchResults> = mutableResults.asStateFlow()

        private var query: String = ""
        private var latest: SearchResults = SearchResults.EMPTY

        // Serialises search() and loadMoreResults(): the query write and the paginator call happen
        // together, so a load-more can only ever fetch the next page of the query the paginator's
        // contents came from. Sign-out does not take it: `paginator.reset()` does wait for a fetch
        // already in flight (on the paginator's own lock), but sign-out does not queue behind the
        // searches and load-mores waiting here, and the session guard already drops what a
        // superseded search publishes.
        private val searchLock = Mutex()

        // ONE paginator for the life of the repository, whose fetch reads `query` when it runs.
        // `restart()` resets the page counter, so a new query needs no new instance — and a
        // stable instance keeps `paginator.items` a single flow that callers can hold.
        private val paginator =
            PagePaginator { page ->
                val response = api.searchMedia(query = query, page = page)
                latest = response.toDomain()
                NumberedPage(items = latest.items, hasMore = response.hasMore)
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun search(query: String) {
            val startedIn = session.current()
            searchLock.withLock {
                val previous = this.query
                this.query = query
                try {
                    paginator.restart()
                } catch (failure: Throwable) {
                    // PagePaginator.restart() mutates nothing when its fetch throws (cancellation
                    // included), so the paginator's contents still belong to `previous`. `query`
                    // must always name the query those contents came from, or a later
                    // loadMoreResults() appends the failed query's page 2 to the old results.
                    // Conditional: a sign-out may have reset `query` meanwhile (it does not take the
                    // lock), and restoring over that would resurrect the previous account's query.
                    if (this.query == query) this.query = previous
                    throw failure
                }
            }
            publish(startedIn)
        }

        override suspend fun loadMoreResults() {
            val startedIn = session.current()
            searchLock.withLock { paginator.loadMore() }
            publish(startedIn)
        }

        /** Sign-out: the last search and its results go (they carry this account's library state). */
        override suspend fun clearUserData() {
            session.end {
                paginator.reset()
                query = ""
                latest = SearchResults.EMPTY
                mutableResults.value = SearchResults.EMPTY
            }
        }

        override suspend fun detail(mediaId: String): Media = api.mediaDetail(mediaId).toDomain()

        override suspend fun episodes(mediaId: String): EpisodeList = api.mediaEpisodes(mediaId).toDomain()

        override suspend fun resolve(
            source: MediaSource,
            externalId: String,
        ): Media =
            api
                .resolveMedia(
                    ResolveMediaRequestDto(source = source.name.lowercase(), externalId = externalId),
                ).toDomain()

        private suspend fun publish(startedIn: Long) {
            session.ifStill(startedIn) { mutableResults.value = latest.copy(items = paginator.items.value) }
        }
    }
