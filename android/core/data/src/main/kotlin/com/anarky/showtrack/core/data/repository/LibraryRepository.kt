package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.ImportSummary
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only data-layer type any `:feature:*` module ever sees. Everything behind it — Retrofit,
 * Room, the DTOs, the entities — is `implementation`-scoped inside `:core:data` and invisible
 * from a feature's compile classpath, which is architecture rule 2 made structural rather than
 * conventional.
 *
 * There is no use-case layer between this and a ViewModel by decision: a use case per method
 * would be one class each forwarding a single call.
 *
 * `@Suppress("TooManyFunctions")` (task 9b.6 added the eleventh, [importAniList]). The outcome —
 * keep it one interface, don't split — is right; the ORIGINAL reasoning here was not (round 1,
 * task 9b.6 fix round, a review finding). It is not shared implementation state that justifies
 * this: [importAniList] shares none of it — no paginator, no cache, no favourites view — it is a
 * one-line `api.importAniList(...).toDomain()` behind a `try`/`catch`, and would bind cleanly to
 * its own stateless class with no loss of cohesion to the STATE. The honest argument is cohesion
 * of the SEAM, not of the implementation behind it: a `:feature:*` module is meant to see ONE
 * library-domain interface (architecture rule 2's whole point), and splitting the moment one
 * member happens not to share mutable state with the rest would make "which interface do I
 * inject" a fact about `LibraryRepositoryImpl`'s internals a caller has no business knowing — the
 * same reasoning [com.anarky.showtrack.core.network.api.ShowTrackApi.library]'s `LongParameterList`
 * suppression carries for a parallel case, a split that exists only to satisfy the linter.
 *
 * The cost this suppression carries, worth stating rather than omitting: it sits on the TYPE, so
 * the ratchet it disables is off PERMANENTLY, not just for this one addition past the threshold.
 * A twelfth, fifteenth, or twentieth method added here later gets no signal from `TooManyFunctions`
 * at all — nothing enforces that a future addition belongs on this seam the way [importAniList]
 * genuinely does; that judgement has to be made by hand at review time, every time, from now on.
 */
@Suppress("TooManyFunctions")
interface LibraryRepository {
    /** Cold-start content, from the cache, then whatever [refresh] last wrote over it. */
    fun observeLibrary(): Flow<List<LibraryEntry>>

    /** Discards the paged state and re-reads from the first page. */
    suspend fun refresh()

    /** Appends the next page, or does nothing once the list is exhausted. */
    suspend fun loadMore()

    /** Re-queries from page one under [filter]. Only the default filter is cached (decision C-B). */
    suspend fun applyFilter(filter: LibraryFilter)

    /** `POST /v1/library`, then refreshes so the new title appears in the list (decision C-K). */
    suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry

    suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry

    /** Null means "not in your library" — not an error (decision C-C). */
    suspend fun entryForMedia(mediaId: String): LibraryEntry?

    /**
     * `GET /v1/library?favorite=true`'s accumulated pages, for `:feature:favorites` (task 9b.4,
     * decision D-H). Backed by its OWN [com.anarky.showtrack.core.data.paging.CursorPaginator]
     * instance, entirely separate from the one behind [observeLibrary]/[refresh]/[loadMore]/
     * [applyFilter]: Library and Favorites are both `TopLevelDestination`s with saved state and
     * can be open at once, so sharing one paginator would make switching tabs reset the OTHER
     * screen's scroll position and page counter.
     *
     * Network-only, no Room cache — see [LibraryRepositoryImpl]'s KDoc on the field backing this.
     */
    val favoriteEntries: StateFlow<List<LibraryEntry>>

    /** Reload the favourites view from the first page, replacing whatever [favoriteEntries] holds. */
    suspend fun refreshFavorites()

    /** Appends the next page of [favoriteEntries], or does nothing once it is exhausted. */
    suspend fun loadMoreFavorites()

    /**
     * `GET /v1/library/stats` (task 9b.5, decision D-F's stats half). No cache and no `StateFlow`
     * upstream, unlike [observeLibrary]/[favoriteEntries] — this is a one-shot read the caller
     * (`ProfileViewModel`) drives itself and re-issues on its own schedule, the same shape
     * [add]/[update]/[entryForMedia] already have.
     */
    suspend fun libraryStats(): LibraryStats

    /**
     * `POST /v1/library/import/anilist` (task 9b.6, backend decision 4-H). One-shot, the same
     * shape [libraryStats] has: no cache, no `StateFlow` upstream — the caller (`ImportViewModel`)
     * drives it once per submit and holds the result itself.
     *
     * Throws [com.anarky.showtrack.core.model.ImportFailure] on any failure, translated at the
     * `LibraryRepositoryImpl` boundary (decision C-R) — never a raw `retrofit2.HttpException`.
     *
     * Architecture rule 7: read-only and one-way, permanently. Nothing on this side, or on the
     * server, ever writes back to AniList as a consequence of calling this.
     */
    suspend fun importAniList(username: String): ImportSummary
}
