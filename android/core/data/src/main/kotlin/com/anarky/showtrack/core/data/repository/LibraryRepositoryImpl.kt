package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.mapper.toEntity
import com.anarky.showtrack.core.data.paging.CursorPaginator
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.ImportFailure
import com.anarky.showtrack.core.model.ImportSummary
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.ImportAniListRequest
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20
private const val HTTP_NOT_FOUND = 404
private const val HTTP_UNPROCESSABLE_ENTITY = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_GATEWAY_TIMEOUT = 504
private val UPSTREAM_FAILURE_CODES = setOf(HTTP_TOO_MANY_REQUESTS, HTTP_BAD_GATEWAY, HTTP_GATEWAY_TIMEOUT)

/**
 * `@Singleton` here on the CLASS rather than on `DataModule`'s `@Binds` method: scoping the bind
 * would scope only the [LibraryRepository] interface, leaving anyone who injected this type
 * concretely with a second instance. The scope belongs to the type because the state does —
 * [paginator] and [filter] carry the cursor, the accumulated pages and the active view as
 * instance state, so a per-consumer instance would restart pagination from page one for every
 * collector.
 *
 * `@Suppress("TooManyFunctions")`: mirrors [LibraryRepository]'s own suppression, for the same
 * reason — see that interface's KDoc.
 */
@Suppress("TooManyFunctions")
@Singleton
class LibraryRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
        private val dao: LibraryDao,
    ) : LibraryRepository {
        // What view the paginator's CURRENT contents belong to. Every read of it inside the
        // `fetch` lambda below must agree with what `paginator` actually holds, which is the
        // invariant `applyFilter` has to preserve across a throw — see its comment.
        private val filter = MutableStateFlow(LibraryFilter())

        // ONE paginator whose fetch reads `filter.value` when it runs. `restart()` drops the
        // cursor, which it MUST: a cursor encodes its sort key, so replaying one under a
        // different sort is a 400 at best and silently skipped rows at worst — changing the
        // filter is therefore never a reason to keep the old instance's cursor around, and never
        // a reason to allocate a new instance either.
        private val paginator =
            CursorPaginator<LibraryEntry> { cursor ->
                val current = filter.value
                val page =
                    api.library(
                        cursor = cursor,
                        limit = PAGE_SIZE,
                        status = current.status?.name?.lowercase(),
                        sort = current.sort.wire,
                        mediaId = null,
                        favorite = null,
                    )
                Page(page.items.map(LibraryEntryDto::toDomain), page.nextCursor)
            }

        // The most recently FETCHED page's items — not the accumulated list. Mirrors
        // `RecommendationRepositoryImpl.lastFetchedPage`: `CursorPaginator.loadMore()` itself
        // returns `Unit`, so this is the only way [loadMoreFavorites] learns what a successful
        // fetch just added, as opposed to re-reading `favoritesPaginator.items.value` — which
        // `CursorPaginator.restart()`'s own KDoc warns against precisely because "[items] can have
        // grown by the time the caller looks at it".
        //
        // This trades that race for a DIFFERENT, narrower one, not a race-free design (review
        // finding, round 2 — an earlier version of this comment overstated what this actually
        // buys): [loadMoreFavorites] reads this field AFTER `favoritesPaginator.loadMore()`
        // returns, so two genuinely overlapping calls could have the second call's fetch overwrite
        // this field before the first call's `loadMoreFavorites()` reads it — losing one page and
        // duplicating another, a window the old `favoriteEntries = favoritesPaginator.items`
        // passthrough did not have. Unreachable today only because every caller serialises these
        // calls — `FavoritesViewModel.loadMore`'s own `loadingMore` guard — the same way it is
        // unreachable in `RecommendationRepositoryImpl`, whose identical shape this mirrors.
        private var lastFetchedFavoritesPage: List<LibraryEntry> = emptyList()

        // A SEPARATE CursorPaginator from [paginator] above (this class's own KDoc / task 9b.4,
        // decision D-H): Library and Favorites are both `TopLevelDestination`s with saved state
        // and can be open at once, so sharing one paginator would make switching tabs reset the
        // OTHER screen's scroll position and page counter. `favorite = true` is the only filter
        // this fetch ever sends — status/sort/mediaId stay null/default because Favorites has no
        // tabs or sort control (decision D-H's "layout is duplicated, the trap is not").
        private val favoritesPaginator =
            CursorPaginator<LibraryEntry> { cursor ->
                val page =
                    api.library(
                        cursor = cursor,
                        limit = PAGE_SIZE,
                        status = null,
                        sort = null,
                        mediaId = null,
                        favorite = true,
                    )
                val items = page.items.map(LibraryEntryDto::toDomain)
                lastFetchedFavoritesPage = items
                Page(items, page.nextCursor)
            }

        // A SEPARATE published list from `favoritesPaginator.items`, deliberately — not a
        // passthrough the way an earlier version of this class had. `refreshFavorites`/
        // `loadMoreFavorites` set this explicitly from what `restart()`/[lastFetchedFavoritesPage]
        // actually returned, never by re-reading `favoritesPaginator.items` after the fact — see
        // [lastFetchedFavoritesPage]'s KDoc for why that distinction is the whole point.
        private val mutableFavoriteEntries = MutableStateFlow<List<LibraryEntry>>(emptyList())
        override val favoriteEntries: StateFlow<List<LibraryEntry>> = mutableFavoriteEntries.asStateFlow()

        /**
         * The cache wins only before the first network page arrives, and only for the default
         * view. Under a filter the cached rows are the WRONG rows, so they are never shown — an
         * empty filtered list renders as empty rather than as the unfiltered cache, which is why
         * the `else` branch below is `paged` with no `ifEmpty` fallback.
         *
         * `ifEmpty` on the default branch is a cold-start sentinel, not the failure path: a
         * failed [refresh] leaves [paginator] untouched (see its KDoc), so `paged` still holds
         * whatever was there before and this never falls back to the cache on a failure. Its one
         * inherent edge: a [refresh] that legitimately answers zero rows is indistinguishable
         * from "nothing fetched yet" until [dao]'s write a moment later catches up, so a
         * genuinely empty default library flickers the stale cache for a frame. Not worth chasing
         * for a single-frame flicker on an empty library.
         *
         * `distinctUntilChanged` is not tidying: [combine] re-emits on every emission of ANY of
         * the three sources, and both [refresh] and [applyFilter] move more than one of them in
         * the same call ([refresh] loads the paginator and then writes the cache; [applyFilter]
         * additionally moves [filter]), so without it a single logical update delivers duplicate
         * lists to a collector.
         */
        override fun observeLibrary(): Flow<List<LibraryEntry>> =
            combine(
                dao.observeAll().map { entities -> entities.map(LibraryEntryEntity::toDomain) },
                paginator.items,
                filter,
            ) { cached, paged, current ->
                if (current.isDefault) paged.ifEmpty { cached } else paged
            }.distinctUntilChanged()

        override suspend fun refresh() {
            // The RETURNED page, never a re-read of `paginator.items.value`: CursorPaginator.restart
            // fetches before it mutates, so a failed refresh leaves the paginator's state — and the
            // on-screen list — exactly as it was.
            val firstPage = paginator.restart()
            // Decision C-B: only the default view is cached. Caching a filtered page would make
            // Room a queryable mirror of fifteen (status x sort) combinations, which is the
            // source-of-truth inversion architecture rule 2 forbids.
            if (filter.value.isDefault) {
                dao.replaceAll(firstPage.map(LibraryEntry::toEntity))
            }
        }

        override suspend fun loadMore() = paginator.loadMore()

        /**
         * Sets [filter] BEFORE the fetch, because the `fetch` lambda above reads `filter.value`
         * when it runs — the new filter has to be visible for [refresh] to query under it.
         *
         * That ordering is exactly what makes a failed [refresh] dangerous here: `restart()`
         * mutates nothing on the paginator when its fetch throws, so [paginator] still holds the
         * OLD filter's cursor and pages, while [filter] would be left pointing at the new one —
         * two pieces of state that must always agree, now disagreeing. A later [loadMore] would
         * then fetch the NEW filter's "page 2" with the OLD filter's cursor: at best a 400 from
         * the backend, at worst a page that silently skips or duplicates rows because the cursor
         * was encoded for a different sort column. This is task 9a.4's `MediaRepositoryImpl.search`
         * bug in a new disguise — same fix: capture the previous value and restore it before
         * rethrowing, so `filter` always names the filter [paginator]'s current contents came from.
         */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun applyFilter(filter: LibraryFilter) {
            val previous = this.filter.value
            this.filter.value = filter
            try {
                refresh()
            } catch (cancellation: CancellationException) {
                this.filter.value = previous
                throw cancellation
            } catch (failure: Exception) {
                this.filter.value = previous
                throw failure
            }
        }

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry {
            val created =
                api
                    .addLibraryEntry(
                        AddLibraryEntryRequest(source = source.name.lowercase(), externalId = externalId),
                    ).toDomain()
            // Decision C-K: without this the user returns from the detail screen to a list that
            // does not contain what they just added. A network round trip is acceptable here
            // because `add` is a one-off action, unlike `update`'s per-tap edits below.
            refresh()
            return created
        }

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry {
            val updated = api.updateLibraryEntry(entryId, patch.toJson()).toDomain()
            // A single-row upsert rather than a full refresh: an edit is one known row, and a
            // network round trip per progress tap would be felt. `insertAll` is REPLACE-on-conflict
            // (LibraryDao), so `insertAll(listOf(...))` upserts this one row rather than needing a
            // dedicated DAO method. Accepted consequence: an edited entry outside the cached first
            // page gets ADDED to the cache; `observeAll()` orders by `updated_at DESC` so it sorts
            // to the top, and the next `refresh()` rebuilds the cache to match the server anyway.
            dao.insertAll(listOf(updated.toEntity()))
            return updated
        }

        /**
         * Null means "not in your library" (decision C-C), not an error — the backend answers an
         * empty page rather than a 404. `limit = 1` cannot truncate a real match: `UserMedia`
         * carries `UniqueConstraint(user_id, media_id)` server-side, so at most one row can ever
         * come back for a given [mediaId].
         */
        override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
            api
                .library(cursor = null, limit = 1, status = null, sort = null, mediaId = mediaId, favorite = null)
                .items
                .firstOrNull()
                ?.toDomain()

        /**
         * `favoritesPaginator.restart()`'s RETURNED page, not a re-read of [favoriteEntries] or
         * `favoritesPaginator.items.value` — the same "fetch before mutate, use what it handed
         * back" discipline [refresh] above and `RecommendationRepositoryImpl.refresh` both follow,
         * and for the identical reason: a failed restart leaves [mutableFavoriteEntries] exactly
         * as it was (this line is never reached when the fetch throws), and a caller that instead
         * re-read the paginator's own list after the fact would be exposed to whatever a
         * concurrently-racing [loadMoreFavorites] had appended in the meantime — see
         * [lastFetchedFavoritesPage]'s KDoc.
         */
        override suspend fun refreshFavorites() {
            val firstPage = favoritesPaginator.restart()
            mutableFavoriteEntries.value = firstPage
        }

        /**
         * Appends [lastFetchedFavoritesPage] onto [mutableFavoriteEntries] — never re-publishes
         * the whole of `favoritesPaginator.items.value` — mirroring
         * `RecommendationRepositoryImpl.loadMore`'s own KDoc for why.
         *
         * The `favoritesPaginator.hasMore.value` guard is NOT redundant with `CursorPaginator`'s
         * own `started && cursor == null` no-op check: that guard makes
         * `favoritesPaginator.loadMore()` a harmless no-op for a caller reading
         * `favoritesPaginator.items` directly, but [lastFetchedFavoritesPage] here is a field that
         * OUTLIVES a single call — without this check, an exhausted `loadMore()` fetches nothing
         * and this function would still append the STALE [lastFetchedFavoritesPage] from the last
         * call that actually fetched, duplicating the final page every time a scrolled-to-the-
         * bottom list fires `loadMoreFavorites()` again.
         */
        override suspend fun loadMoreFavorites() {
            if (!favoritesPaginator.hasMore.value) return
            favoritesPaginator.loadMore()
            mutableFavoriteEntries.value = mutableFavoriteEntries.value + lastFetchedFavoritesPage
        }

        /** A plain pass-through — no cache, no paginator, nothing to sequence. */
        override suspend fun libraryStats(): LibraryStats = api.libraryStats().toDomain()

        /**
         * A plain pass-through like [libraryStats] above, plus the one thing [libraryStats] never
         * needs: translating a non-2xx into [ImportFailure] (decision C-R) — [mapImportFailure]
         * does the actual translation, kept as a private top-level function rather than inlined
         * here so it is unit-testable in isolation from the paginator/cache machinery this class
         * otherwise carries.
         */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun importAniList(username: String): ImportSummary =
            try {
                api.importAniList(ImportAniListRequest(username = username)).toDomain()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw mapImportFailure(failure)
            }
    }

/**
 * 404 means no PUBLIC list for that username — [ImportFailure.ListNotPublic]'s own KDoc explains
 * why that single case has to cover both "no such user" and "a private list" rather than picking
 * one. 422 is a malformed username; 429/502/504 are the upstream AniList API itself failing,
 * rate-limiting, or timing out (backend's `HANDLED` table, `app/errors.py`) — collapsed into one
 * [ImportFailure.UpstreamUnavailable] case because none of the three distinguishes an action the
 * screen would take differently from the others: all three mean "try again shortly".
 */
private fun mapImportFailure(failure: Throwable): ImportFailure =
    when {
        failure is HttpException && failure.code() == HTTP_NOT_FOUND -> ImportFailure.ListNotPublic(failure)
        failure is HttpException && failure.code() == HTTP_UNPROCESSABLE_ENTITY ->
            ImportFailure.InvalidUsername(failure)
        failure is HttpException && failure.code() in UPSTREAM_FAILURE_CODES ->
            ImportFailure.UpstreamUnavailable(failure)
        failure is IOException -> ImportFailure.Offline(failure)
        else -> ImportFailure.Unexpected(failure)
    }

/**
 * Built by hand rather than serialised from a data class, because `score` is tri-state: absent,
 * explicit null, or a value. See `ShowTrackApi.updateLibraryEntry`.
 *
 * The score goes over as a STRING, matching what the server sends back — a JSON number here would
 * be an IEEE 754 double and reintroduce exactly the drift `NUMERIC(3,1)` exists to prevent
 * (backend decision 4-N).
 */
private fun LibraryPatch.toJson(): JsonObject =
    buildJsonObject {
        status?.let { put("status", it.name.lowercase()) }
        progress?.let { put("progress", it) }
        favorite?.let { put("favorite", it) }
        when (val change = score) {
            null -> Unit
            is ScoreChange.Clear -> put("score", JsonNull)
            is ScoreChange.Set -> put("score", change.value.toPlainString())
        }
    }
