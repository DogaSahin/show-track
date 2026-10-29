package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 *
 * Home's surface ([observeHomeCache], [refreshHome], [sectionPage]) is driven by `HomeSectionsTest`
 * through the mutable fields below; their defaults are an empty library, which keeps
 * [LibraryEntryHiltTest]'s composition quiet.
 */
internal class FakeLibraryRepository
    @Inject
    constructor() : LibraryRepository {
        val homeCache = MutableStateFlow<Map<LibrarySection, List<LibraryEntry>>>(emptyMap())
        var homePages: Map<LibrarySection, Page<LibraryEntry>> = emptyMap()
        var refreshHomeFailure: Throwable? = null

        // Stands in for a request still in flight: awaited before the failure/success check.
        var refreshHomeGate: CompletableDeferred<Unit>? = null
        val refreshHomeSorts = mutableListOf<LibrarySort>()

        /** Pages served by [sectionPage], keyed by section and the cursor asked for. */
        var sectionPages: Map<Pair<LibrarySection, String?>, Page<LibraryEntry>> = emptyMap()
        var sectionPageFailure: Throwable? = null
        var sectionPageGate: CompletableDeferred<Unit>? = null
        val sectionPageCalls = mutableListOf<SectionPageCall>()

        var stats: LibraryStats? = null

        data class SectionPageCall(
            val section: LibrarySection,
            val sort: LibrarySort,
            val cursor: String?,
            val limit: Int,
        )

        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())

        override suspend fun refresh() = Unit

        override fun observeHomeCache(): Flow<Map<LibrarySection, List<LibraryEntry>>> = homeCache

        override suspend fun refreshHome(
            sort: LibrarySort,
            perSection: Int,
        ): Map<LibrarySection, Page<LibraryEntry>> {
            refreshHomeSorts += sort
            refreshHomeGate?.await()
            refreshHomeFailure?.let { throw it }
            return homePages
        }

        override suspend fun sectionPage(
            section: LibrarySection,
            sort: LibrarySort,
            cursor: String?,
            limit: Int,
        ): Page<LibraryEntry> {
            sectionPageCalls += SectionPageCall(section, sort, cursor, limit)
            sectionPageGate?.await()
            sectionPageFailure?.let { throw it }
            return sectionPages[section to cursor] ?: Page(emptyList(), nextCursor = null)
        }

        override suspend fun loadMore() = Unit

        override suspend fun applyFilter(filter: LibraryFilter) = Unit

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry = error("not exercised by LibraryEntryHiltTest")

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
        ): LibraryEntry = error("not exercised by LibraryEntryHiltTest")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
            error("not exercised by LibraryEntryHiltTest")

        override suspend fun favoritesPage(
            type: MediaType?,
            sort: LibrarySort,
            cursor: String?,
            limit: Int,
        ): Page<LibraryEntry> = Page(emptyList(), null)

        // `LibraryViewModel.refreshOverview` swallows this failure; a test that wants counts sets [stats].
        override suspend fun libraryStats(): LibraryStats = stats ?: error("no stats in this test")

        override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

        override suspend fun allWatching(): List<LibraryEntry> = emptyList()

        override suspend fun importAniList(username: String) = error("not exercised by LibraryEntryHiltTest")
    }
