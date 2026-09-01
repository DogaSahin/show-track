package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.data.mapper.toEntity
import com.anarky.showtrack.core.data.paging.CursorPaginator
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.LibraryEntryEntity
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20

/**
 * `@Singleton` here on the CLASS rather than on `DataModule`'s `@Binds` method: scoping the bind
 * would scope only the [LibraryRepository] interface, leaving anyone who injected this type
 * concretely with a second instance. The scope belongs to the type because the state does —
 * [paginator] and [filter] carry the cursor, the accumulated pages and the active view as
 * instance state, so a per-consumer instance would restart pagination from page one for every
 * collector.
 */
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
                    )
                Page(page.items.map(LibraryEntryDto::toDomain), page.nextCursor)
            }

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
                .library(cursor = null, limit = 1, status = null, sort = null, mediaId = mediaId)
                .items
                .firstOrNull()
                ?.toDomain()
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
