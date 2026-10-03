package com.anarky.showtrack.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal const val PODIUM_SIZE = 3
internal const val FAVORITES_PAGE_SIZE = 20

/**
 * The Favorites tab: three reads per refresh (the podium and the two shelves, in parallel), each
 * shelf paging on its own afterwards, and removal with Undo.
 *
 * **Refresh on resume, not in `init`.** `FavoritesScreen`'s `LifecycleResumeEffect` is the only
 * caller of [refresh] for both the first load and every return from show details, where a
 * favourite or a score can have changed; this ViewModel outlives that trip.
 *
 * **Paging can't double up.** Every refresh bumps [generation]; a page that lands after a newer
 * refresh started is dropped instead of being appended to rows it never belonged to, and appends
 * are de-duplicated by entry id regardless. A repeated id is a composition crash in a keyed lazy
 * list, which is exactly how this screen once failed.
 *
 * **Removal is optimistic.** The poster disappears at once and comes back in the same place if the
 * server refuses, or when Undo is tapped. [removals] keeps a refresh that raced the request from
 * showing it again.
 */
@HiltViewModel
class FavoritesViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
        val state: StateFlow<FavoritesUiState> = mutableState.asStateFlow()

        private val eventChannel = Channel<FavoritesEvent>(Channel.BUFFERED)
        val events: Flow<FavoritesEvent> = eventChannel.receiveAsFlow()

        private var generation = 0
        private var refreshInFlight = false
        private val removals = FavoriteRemovals()
        private val removedFrom = mutableMapOf<String, Placement>()

        /**
         * Reloads the podium and both shelves from their first page. A refresh over a populated
         * screen never blanks it: on failure the rows stay and are marked stale, and only a first
         * load with nothing on screen becomes [FavoritesUiState.Error]. A second call while one is
         * in flight is dropped.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            if (refreshInFlight) return
            refreshInFlight = true
            if (mutableState.value !is FavoritesUiState.Success) mutableState.value = FavoritesUiState.Loading
            val launchedAt = ++generation
            viewModelScope.launch {
                try {
                    val loaded =
                        coroutineScope {
                            // One more than the podium shows: the reserve steps up when a podium title is removed.
                            val podium =
                                async { repository.favoritesPage(null, LibrarySort.SCORE, null, PODIUM_SIZE + 1) }
                            val anime = async { firstPage(MediaType.ANIME) }
                            val tv = async { firstPage(MediaType.TV) }
                            FavoritesUiState.Success(
                                podium =
                                    podium
                                        .await()
                                        .items
                                        .filter { it.score != null }
                                        .visible(launchedAt),
                                anime = anime.await().visible(launchedAt),
                                tv = tv.await().visible(launchedAt),
                            )
                        }
                    removals.settle(launchedAt)
                    mutableState.value = loaded
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // A load-more still in flight was started before this refresh, so its page will
                    // be dropped as stale: clear its spinner here or the shelf never pages again.
                    val showing = mutableState.value as? FavoritesUiState.Success
                    mutableState.value =
                        showing?.copy(
                            isStale = true,
                            anime = showing.anime.copy(loadingMore = false),
                            tv = showing.tv.copy(loadingMore = false),
                        ) ?: FavoritesUiState.Error(failure)
                } finally {
                    refreshInFlight = false
                }
            }
        }

        /** The next page of one shelf, if it has one and isn't already loading or being refreshed. */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore(type: MediaType) {
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            val shelf = current.shelf(type)
            val cursor = shelf.nextCursor ?: return
            if (shelf.loadingMore || refreshInFlight) return
            val launchedAt = generation
            updateShelf(type) { it.copy(loadingMore = true, pageError = null) }
            viewModelScope.launch {
                try {
                    val page = repository.favoritesPage(type, LibrarySort.SCORE, cursor, FAVORITES_PAGE_SIZE)
                    if (launchedAt != generation) return@launch
                    updateShelf(type) { latest ->
                        latest.copy(
                            entries =
                                (latest.entries + page.items.filterNot { removals.hides(it.id, launchedAt) })
                                    .distinctBy(LibraryEntry::id),
                            nextCursor = page.nextCursor,
                            loadingMore = false,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (launchedAt ==
                        generation
                    ) {
                        updateShelf(type) { it.copy(loadingMore = false, pageError = failure) }
                    }
                }
            }
        }

        /** Takes [entry] off the podium and its shelf at once, then asks the server to unfavourite it. */
        @Suppress("TooGenericExceptionCaught")
        fun remove(entry: LibraryEntry) {
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            if (!removals.begin(entry.id)) return
            removedFrom[entry.id] = current.placementOf(entry)
            mutableState.value = current.without(entry.id)
            viewModelScope.launch {
                try {
                    repository.update(entry.id, LibraryPatch(favorite = false))
                    removals.confirm(entry.id, generation)
                    eventChannel.send(FavoritesEvent.Removed(entry))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    removals.forget(entry.id)
                    putBack(entry)
                    eventChannel.send(FavoritesEvent.EditFailed)
                }
            }
        }

        /** Puts [entry] back where it was at once, then asks the server to favourite it again. */
        @Suppress("TooGenericExceptionCaught")
        fun undo(entry: LibraryEntry) {
            removals.forget(entry.id)
            val placement = removedFrom[entry.id] ?: return
            putBack(entry)
            viewModelScope.launch {
                try {
                    repository.update(entry.id, LibraryPatch(favorite = true))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Still removed on the server: take it away again, and keep it hidden from a
                    // refresh that raced this request.
                    removals.begin(entry.id)
                    removals.confirm(entry.id, generation)
                    removedFrom[entry.id] = placement
                    (mutableState.value as? FavoritesUiState.Success)?.let { mutableState.value = it.without(entry.id) }
                    eventChannel.send(FavoritesEvent.EditFailed)
                }
            }
        }

        private suspend fun firstPage(type: MediaType): FavoriteShelf {
            val page = repository.favoritesPage(type, LibrarySort.SCORE, null, FAVORITES_PAGE_SIZE)
            return FavoriteShelf(entries = page.items, nextCursor = page.nextCursor)
        }

        private fun List<LibraryEntry>.visible(launchedAt: Int) = filterNot { removals.hides(it.id, launchedAt) }

        private fun FavoriteShelf.visible(launchedAt: Int) = copy(entries = entries.visible(launchedAt))

        private fun putBack(entry: LibraryEntry) {
            val placement = removedFrom.remove(entry.id) ?: return
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            mutableState.value = current.with(entry, placement)
        }

        private inline fun updateShelf(
            type: MediaType,
            transform: (FavoriteShelf) -> FavoriteShelf,
        ) {
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            mutableState.value =
                when (type) {
                    MediaType.ANIME -> current.copy(anime = transform(current.anime))
                    MediaType.TV -> current.copy(tv = transform(current.tv))
                }
        }
    }

/** Where a removed favourite was, so Undo and a refused removal put it back in the same place. */
internal data class Placement(
    val podiumIndex: Int,
    val shelfIndex: Int,
)

internal fun FavoritesUiState.Success.shelf(type: MediaType): FavoriteShelf =
    when (type) {
        MediaType.ANIME -> anime
        MediaType.TV -> tv
    }

internal fun FavoritesUiState.Success.placementOf(entry: LibraryEntry) =
    Placement(
        podiumIndex = podium.indexOfFirst { it.id == entry.id },
        shelfIndex = shelf(entry.media.type).entries.indexOfFirst { it.id == entry.id },
    )

internal fun FavoritesUiState.Success.without(entryId: String) =
    copy(
        podium = podium.filterNot { it.id == entryId },
        anime = anime.copy(entries = anime.entries.filterNot { it.id == entryId }),
        tv = tv.copy(entries = tv.entries.filterNot { it.id == entryId }),
    )

internal fun FavoritesUiState.Success.with(
    entry: LibraryEntry,
    placement: Placement,
): FavoritesUiState.Success {
    val restoredPodium = if (placement.podiumIndex >= 0) podium.insertedAt(placement.podiumIndex, entry) else podium
    val shelf = shelf(entry.media.type)
    val restoredShelf =
        if (placement.shelfIndex >=
            0
        ) {
            shelf.copy(entries = shelf.entries.insertedAt(placement.shelfIndex, entry))
        } else {
            shelf
        }
    return when (entry.media.type) {
        MediaType.ANIME -> copy(podium = restoredPodium, anime = restoredShelf)
        MediaType.TV -> copy(podium = restoredPodium, tv = restoredShelf)
    }
}

/** [entry] at [index] (clamped), unless an entry with its id is already there. */
internal fun List<LibraryEntry>.insertedAt(
    index: Int,
    entry: LibraryEntry,
): List<LibraryEntry> =
    if (any { it.id == entry.id }) {
        this
    } else {
        toMutableList().apply { add(index.coerceIn(0, size), entry) }
    }
