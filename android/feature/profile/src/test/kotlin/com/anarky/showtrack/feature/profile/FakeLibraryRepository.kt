package com.anarky.showtrack.feature.profile

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Shared by [ProfileViewModelTest] and [ProfileResumeTest] (round 2) — both exercise
 * [ProfileViewModel] against a fake rather than
 * [com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl], which would need Retrofit,
 * neither on this module's compile classpath (architecture rule 2).
 *
 * [libraryStats] and, since task 9b.6, [importAniList] are functional — every other member
 * `error(...)`, the same discrimination `:feature:favorites`' own `FakeLibraryRepository` uses: a
 * [ProfileViewModel] or [ImportViewModel] that accidentally reached the general library surface
 * fails LOUDLY, with that message, rather than silently returning the wrong thing. Shared by
 * [ProfileViewModelTest]/[ProfileResumeTest] (stats) and [ImportViewModelTest] (import) — the two
 * ViewModels never touch the same members, so nothing here has to distinguish which one is asking.
 *
 * [statsGate], when set, is what lets a test observe [ProfileViewModel.statsState] WHILE
 * [libraryStats] is suspended — mirroring `FavoritesViewModelTest`'s `FakeLibraryRepository.refreshGate`,
 * needed for the identical reason: a fake that always resolves synchronously can never make a
 * wrongly-shown [LibraryStatsUiState.Loading] (or a wrongly-replaced [LibraryStatsUiState.Error])
 * observable mid-flight. [importAniList] has no equivalent gate: no [ImportViewModel] test needs
 * to observe mid-flight state, unlike the stats resume behaviour this gate exists for.
 */
internal class FakeLibraryRepository(
    var statsResult: LibraryStats = EMPTY_STATS,
    var statsFailure: Throwable? = null,
    var importResult: ImportSummary = ImportSummary(imported = 0, skipped = 0, failed = 0, truncated = false),
    var importFailure: Throwable? = null,
) : LibraryRepository {
    var statsGate: CompletableDeferred<Unit>? = null

    var importCalls = 0
        private set

    // Round 1 fix (task 9b.6 fix round, M4): without this, `importAniList("")` was
    // indistinguishable from `importAniList("someone")` to every test using this fake — the count
    // moved, but the ARGUMENT itself was discarded and unverifiable, which is exactly the gap
    // between "the call happened" and "the call happened with what the caller actually typed".
    var lastUsername: String? = null
        private set

    // Round 1's own regression guard: proves `init`/alerts toggles reach `libraryStats()` zero
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
    ): LibraryEntry = error("not exercised by ProfileViewModel")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by ProfileViewModel")

    override suspend fun favoritesPage(
        type: MediaType?,
        sort: LibrarySort,
        cursor: String?,
        limit: Int,
    ): Page<LibraryEntry> = Page(emptyList(), null)

    override suspend fun libraryStats(): LibraryStats {
        statsCalls++
        statsGate?.await()
        statsFailure?.let { throw it }
        return statsResult
    }

    override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

    var watching: List<LibraryEntry> = emptyList()
    var allWatchingFailure: Throwable? = null

    // Runs inside allWatching(), so a test can change the world mid-fetch (e.g. sign out).
    var duringAllWatching: () -> Unit = {}

    override suspend fun allWatching(): List<LibraryEntry> {
        duringAllWatching()
        allWatchingFailure?.let { throw it }
        return watching
    }

    override suspend fun importAniList(username: String): ImportSummary {
        importCalls++
        lastUsername = username
        importFailure?.let { throw it }
        return importResult
    }

    private companion object {
        val EMPTY_STATS =
            LibraryStats(
                total = 0,
                byStatus = emptyMap(),
                averageScore = null,
                ratedCount = 0,
                episodesWatched = 0,
                topGenres = emptyList(),
                addedThisMonth = 0,
                favorites = 0,
            )
    }
}
