package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared by [ProfileViewModelTest] and [ProfileResumeTest] (round 2) — both exercise
 * [ProfileViewModel] against a fake rather than
 * [com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl], which would need Retrofit,
 * neither on this module's compile classpath (architecture rule 2).
 *
 * Only [libraryStats] is functional — every other member `error(...)`, the same discrimination
 * `:feature:favorites`' own `FakeLibraryRepository` uses: a [ProfileViewModel] that accidentally
 * reached the general library surface instead of [LibraryRepository.libraryStats] fails LOUDLY,
 * with that message, rather than silently returning the wrong thing.
 *
 * [statsGate], when set, is what lets a test observe [ProfileViewModel.statsState] WHILE
 * [libraryStats] is suspended — mirroring `FavoritesViewModelTest`'s `FakeLibraryRepository.refreshGate`,
 * needed for the identical reason: a fake that always resolves synchronously can never make a
 * wrongly-shown [LibraryStatsUiState.Loading] (or a wrongly-replaced [LibraryStatsUiState.Error])
 * observable mid-flight.
 */
internal class FakeLibraryRepository(
    var statsResult: LibraryStats = EMPTY_STATS,
    var statsFailure: Throwable? = null,
) : LibraryRepository {
    var statsGate: CompletableDeferred<Unit>? = null

    // Round 1's own regression guard: proves `init`/push toggles reach `libraryStats()` zero
    // times, which a state-only assertion (`statsState.value`, still `Loading`) cannot — a
    // ViewModel that fetched and then discarded the result would look identical to one that
    // never fetched at all if only the resulting state were checked. Round 2's `ProfileResumeTest`
    // reuses it to pin the PRODUCTION trigger (`LifecycleResumeEffect`/the retry button), which a
    // ViewModel-only test cannot see at all.
    var statsCalls = 0
        private set

    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by ProfileViewModel")

    override suspend fun refresh(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun loadMore(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by ProfileViewModel")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = error("not exercised by ProfileViewModel")

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("not exercised by ProfileViewModel")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by ProfileViewModel")

    override val favoriteEntries: StateFlow<List<LibraryEntry>> =
        MutableStateFlow(emptyList<LibraryEntry>()).asStateFlow()

    override suspend fun refreshFavorites(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun loadMoreFavorites(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun libraryStats(): LibraryStats {
        statsCalls++
        statsGate?.await()
        statsFailure?.let { throw it }
        return statsResult
    }

    private companion object {
        val EMPTY_STATS = LibraryStats(total = 0, byStatus = emptyMap(), averageScore = null, ratedCount = 0)
    }
}
