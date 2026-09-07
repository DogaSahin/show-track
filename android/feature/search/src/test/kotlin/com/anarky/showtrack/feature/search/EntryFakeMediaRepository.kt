package com.anarky.showtrack.feature.search

import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.SearchResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A SEPARATE fake from [SearchViewModelTest]'s own private `FakeMediaRepository` — that one is
 * `private` to its file, so a `@BindValue` field in [SearchEntryHiltTest] cannot reach it.
 * [searchResult] answers every query identically: the debounced [SearchViewModel.runSearch] this
 * test drives through real typed input never needs to distinguish one query string from another.
 */
internal class EntryFakeMediaRepository(
    var searchResult: SearchResults = SearchResults(items = emptyList(), hasMore = false, degraded = emptyList()),
) : MediaRepository {
    private val mutableSearchResults = MutableStateFlow(searchResult)
    override val searchResults: StateFlow<SearchResults> = mutableSearchResults.asStateFlow()

    override suspend fun search(query: String) {
        mutableSearchResults.value = searchResult
    }

    override suspend fun loadMoreResults() = error("SearchEntryHiltTest does not exercise paging")

    override suspend fun detail(mediaId: String): Media = error("SearchEntryHiltTest does not exercise detail")
}
