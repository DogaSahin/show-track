package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.ImportSummary
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Not exercised by [DiscoverEntryHiltTest]: the row-click binding under test fires directly off
 * `Recommendation.media.id`, never through `DiscoverViewModel.add`. Every member throws — a call
 * reaching this would mean the test exercised the add flow it never intended to.
 */
internal class EntryFakeLibraryRepository : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun refresh(): Unit = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun loadMore(): Unit = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
        error("DiscoverEntryHiltTest does not exercise add")

    override val favoriteEntries: StateFlow<List<LibraryEntry>> =
        MutableStateFlow(emptyList<LibraryEntry>()).asStateFlow()

    override suspend fun refreshFavorites(): Unit = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun loadMoreFavorites(): Unit = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun libraryStats(): LibraryStats = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun importAniList(username: String): ImportSummary =
        error("DiscoverEntryHiltTest does not exercise add")
}
