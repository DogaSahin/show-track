package com.anarky.showtrack.feature.search

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
 * A SEPARATE fake from [SearchViewModelTest]'s own private `FakeLibraryRepository` — that one is
 * `private` to its file, so a `@BindValue` field in [SearchEntryHiltTest] cannot reach it. Only
 * [add] is functional: [SearchEntryHiltTest] drives a real tap-to-add, which is what
 * `navigateToDetail` fires off of — every other member throws, matching `:feature:favorites`' own
 * `FakeLibraryRepository` discrimination.
 */
internal class EntryFakeLibraryRepository(
    var addResult: LibraryEntry,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("SearchEntryHiltTest only exercises add")

    override suspend fun refresh(): Unit = error("SearchEntryHiltTest only exercises add")

    override suspend fun loadMore(): Unit = error("SearchEntryHiltTest only exercises add")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("SearchEntryHiltTest only exercises add")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = addResult

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("SearchEntryHiltTest only exercises add")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("SearchEntryHiltTest only exercises add")

    override val favoriteEntries: StateFlow<List<LibraryEntry>> =
        MutableStateFlow(emptyList<LibraryEntry>()).asStateFlow()

    override suspend fun refreshFavorites(): Unit = error("SearchEntryHiltTest only exercises add")

    override suspend fun loadMoreFavorites(): Unit = error("SearchEntryHiltTest only exercises add")

    override suspend fun libraryStats(): LibraryStats = error("SearchEntryHiltTest only exercises add")

    override suspend fun importAniList(username: String): ImportSummary =
        error("SearchEntryHiltTest only exercises add")
}
