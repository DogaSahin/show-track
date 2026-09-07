package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.SearchResults
import kotlinx.coroutines.flow.StateFlow

interface MediaRepository {
    val searchResults: StateFlow<SearchResults>

    /** Runs a new query from page one, replacing any previous results. */
    suspend fun search(query: String)

    suspend fun loadMoreResults()

    suspend fun detail(mediaId: String): Media
}
