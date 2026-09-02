package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared by [FavoritesViewModelTest] and [FavoritesResumeTest] — both exercise
 * [FavoritesViewModel] against a fake rather than [com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl],
 * which would need Retrofit and Room, neither on this module's compile classpath
 * (architecture rule 2).
 *
 * Only the favourites surface is functional. The GENERAL-purpose surface
 * (`observeLibrary`/`refresh`/`loadMore`/`applyFilter`) — and `add`/`update`/`entryForMedia`,
 * which this ViewModel has no reason to call at all (task 9b.4's brief: "no add here") — all
 * `error(...)` rather than silently no-op: a [FavoritesViewModel] that accidentally reached the
 * general surface instead of [LibraryRepository.favoriteEntries]/[LibraryRepository.refreshFavorites]
 * fails LOUDLY, with that message, rather than silently returning the wrong list — see
 * `FavoritesViewModelTest`'s "the feed reads the favourites surface, never the general library
 * one" for what this discrimination actually proves.
 */
internal class FakeLibraryRepository(
    var refreshResult: List<LibraryEntry> = emptyList(),
    var refreshFailure: Throwable? = null,
    var loadMoreAppends: List<LibraryEntry> = emptyList(),
    var loadMoreFailure: Throwable? = null,
) : LibraryRepository {
    private val mutableFavorites = MutableStateFlow<List<LibraryEntry>>(emptyList())
    override val favoriteEntries: StateFlow<List<LibraryEntry>> = mutableFavorites.asStateFlow()

    var loadMoreCalls = 0
        private set

    var refreshCalls = 0
        private set

    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by FavoritesViewModel")

    override suspend fun refresh(): Unit = error("not exercised by FavoritesViewModel")

    override suspend fun loadMore(): Unit = error("not exercised by FavoritesViewModel")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by FavoritesViewModel")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = error("not exercised by FavoritesViewModel")

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("not exercised by FavoritesViewModel")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by FavoritesViewModel")

    override suspend fun refreshFavorites() {
        refreshCalls++
        refreshFailure?.let { throw it }
        mutableFavorites.value = refreshResult
    }

    override suspend fun loadMoreFavorites() {
        loadMoreCalls++
        loadMoreFailure?.let { throw it }
        mutableFavorites.value = mutableFavorites.value + loadMoreAppends
    }
}
