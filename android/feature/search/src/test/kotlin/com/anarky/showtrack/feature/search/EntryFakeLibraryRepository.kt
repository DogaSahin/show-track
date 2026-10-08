package com.anarky.showtrack.feature.search

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
 * A SEPARATE fake from [SearchViewModelTest]'s own private `FakeLibraryRepository` — that one is
 * `private` to its file, so a `@BindValue` field in [SearchEntryHiltTest] cannot reach it. Only
 * [add] is functional: [SearchEntryHiltTest] drives a real tap-to-add, which is what
 * `navigateToDetail` fires off of — every other member throws, matching `:feature:favorites`' own
 * `FakeLibraryRepository` discrimination.
 */
internal class EntryFakeLibraryRepository(
    var addResult: LibraryEntry,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by SearchEntryHiltTest")

    override suspend fun refresh(): Unit = error("not exercised by SearchEntryHiltTest")

    override suspend fun loadMore(): Unit = error("not exercised by SearchEntryHiltTest")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by SearchEntryHiltTest")

    var addCalls = 0
        private set

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry {
        addCalls++
        return addResult
    }

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
    ): LibraryEntry = error("not exercised by SearchEntryHiltTest")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by SearchEntryHiltTest")

    override suspend fun favoritesPage(
        type: MediaType?,
        sort: LibrarySort,
        cursor: String?,
        limit: Int,
    ): Page<LibraryEntry> = Page(emptyList(), null)

    override suspend fun libraryStats(): LibraryStats = error("not exercised by SearchEntryHiltTest")

    override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

    override suspend fun allWatching(): List<LibraryEntry> = emptyList()

    override suspend fun importAniList(username: String): ImportSummary = error("not exercised by SearchEntryHiltTest")
}
