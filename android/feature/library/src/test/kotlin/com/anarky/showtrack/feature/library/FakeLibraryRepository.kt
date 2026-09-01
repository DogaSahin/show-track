package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * The Hilt-graph counterpart to `LibraryViewModelTest`'s own (file-private, so unreachable here)
 * fake of the same name. Bound through [TestDataModule], this is what a real `@HiltViewModel`
 * `LibraryViewModel`, resolved through `hiltViewModel()` inside a composed `libraryEntry()`, gets
 * handed instead of [com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl] — which
 * would need Retrofit and Room, neither on this module's compile classpath (architecture rule 2)
 * and neither anything [LibraryEntryHiltTest] wants to exercise.
 *
 * `@Inject constructor()` is what lets [TestDataModule]'s `@Binds` method construct this with no
 * `@Provides` boilerplate.
 */
internal class FakeLibraryRepository
    @Inject
    constructor() : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())

        override suspend fun refresh() = Unit

        override suspend fun loadMore() = Unit

        override suspend fun applyFilter(filter: LibraryFilter) = Unit

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry = error("not exercised by LibraryEntryHiltTest")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by LibraryEntryHiltTest")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
            error("not exercised by LibraryEntryHiltTest")
    }
