package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.ImportSummary
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaType
import kotlinx.coroutines.flow.Flow

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

    override suspend fun remove(entryId: String): Unit = error("not used here")

    override suspend fun watchedEpisodes(entryId: String): Set<String> = error("not used here")

    override suspend fun setWatched(
        entryId: String,
        episodeIds: Collection<String>,
        watched: Boolean,
    ): LibraryEntry = error("not used here")

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
        error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun favoritesPage(
        type: MediaType?,
        sort: LibrarySort,
        cursor: String?,
        limit: Int,
    ): Page<LibraryEntry> = Page(emptyList(), null)

    override suspend fun libraryStats(): LibraryStats = error("DiscoverEntryHiltTest does not exercise add")

    override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

    override suspend fun allWatching(): List<LibraryEntry> = emptyList()

    override suspend fun importAniList(username: String): ImportSummary =
        error("DiscoverEntryHiltTest does not exercise add")
}
