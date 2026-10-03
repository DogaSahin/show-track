package com.anarky.showtrack.feature.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.navigation.FavoritesGridRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The See all grid's state: one type's favourites, in the chosen order. */
sealed interface FavoritesGridUiState {
    data object Loading : FavoritesGridUiState

    data class Success(
        val shelf: FavoriteShelf,
        val isStale: Boolean = false,
    ) : FavoritesGridUiState

    data class Error(
        val cause: Throwable,
    ) : FavoritesGridUiState
}

/** The two orders the grid offers. Unscored favourites come last under [SCORE] (the server's order). */
enum class FavoritesGridSort(
    val sort: LibrarySort,
) {
    SCORE(LibrarySort.SCORE),
    TITLE(LibrarySort.TITLE),
}

/**
 * Every favourite of one media type ([FavoritesGridRoute.type]), paged, with a sort and the same
 * optimistic removal and Undo as the tab. The paging and removal rules are [FavoritesViewModel]'s:
 * a page from before the latest refresh or sort change is dropped, appends are de-duplicated, and
 * [FavoriteRemovals] keeps a raced refresh from bringing a removed poster back.
 */
@HiltViewModel
class FavoritesGridViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: LibraryRepository,
    ) : ViewModel() {
        val type: MediaType = mediaTypeOf(savedStateHandle.toRoute<FavoritesGridRoute>().type)

        private val mutableSort = MutableStateFlow(FavoritesGridSort.SCORE)
        val sort: StateFlow<FavoritesGridSort> = mutableSort.asStateFlow()

        private val mutableState = MutableStateFlow<FavoritesGridUiState>(FavoritesGridUiState.Loading)
        val state: StateFlow<FavoritesGridUiState> = mutableState.asStateFlow()

        private val eventChannel = Channel<FavoritesEvent>(Channel.BUFFERED)
        val events: Flow<FavoritesEvent> = eventChannel.receiveAsFlow()

        private var generation = 0
        private val removals = FavoriteRemovals()
        private val removedAt = mutableMapOf<String, Int>()

        /** A new order starts from the first page, blanking the grid: the old order's rows are the wrong rows. */
        fun selectSort(sort: FavoritesGridSort) {
            if (sort == mutableSort.value) return
            mutableSort.value = sort
            mutableState.value = FavoritesGridUiState.Loading
            refresh()
        }

        /** The first page again. Over a populated grid a failure marks it stale rather than erasing it. */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            if (mutableState.value !is FavoritesGridUiState.Success) mutableState.value = FavoritesGridUiState.Loading
            val launchedAt = ++generation
            viewModelScope.launch {
                try {
                    val page = repository.favoritesPage(type, mutableSort.value.sort, null, FAVORITES_PAGE_SIZE)
                    if (launchedAt != generation) return@launch
                    removals.settle(launchedAt)
                    mutableState.value =
                        FavoritesGridUiState.Success(
                            FavoriteShelf(
                                entries = page.items.filterNot { removals.hides(it.id, launchedAt) },
                                nextCursor = page.nextCursor,
                            ),
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (launchedAt != generation) return@launch
                    // As on the tab: a dropped in-flight load-more must not leave its spinner behind.
                    val showing = mutableState.value as? FavoritesGridUiState.Success
                    mutableState.value =
                        showing?.copy(isStale = true, shelf = showing.shelf.copy(loadingMore = false))
                            ?: FavoritesGridUiState.Error(failure)
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            val current = mutableState.value as? FavoritesGridUiState.Success ?: return
            val cursor = current.shelf.nextCursor ?: return
            if (current.shelf.loadingMore) return
            val launchedAt = generation
            updateShelf { it.copy(loadingMore = true, pageError = null) }
            viewModelScope.launch {
                try {
                    val page = repository.favoritesPage(type, mutableSort.value.sort, cursor, FAVORITES_PAGE_SIZE)
                    if (launchedAt != generation) return@launch
                    updateShelf { latest ->
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
                    if (launchedAt == generation) updateShelf { it.copy(loadingMore = false, pageError = failure) }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun remove(entry: LibraryEntry) {
            val current = mutableState.value as? FavoritesGridUiState.Success ?: return
            if (!removals.begin(entry.id)) return
            removedAt[entry.id] = current.shelf.entries.indexOfFirst { it.id == entry.id }
            updateShelf { shelf -> shelf.copy(entries = shelf.entries.filterNot { it.id == entry.id }) }
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

        @Suppress("TooGenericExceptionCaught")
        fun undo(entry: LibraryEntry) {
            removals.forget(entry.id)
            val index = removedAt[entry.id] ?: return
            putBack(entry)
            viewModelScope.launch {
                try {
                    repository.update(entry.id, LibraryPatch(favorite = true))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    removals.begin(entry.id)
                    removals.confirm(entry.id, generation)
                    removedAt[entry.id] = index
                    updateShelf { shelf -> shelf.copy(entries = shelf.entries.filterNot { it.id == entry.id }) }
                    eventChannel.send(FavoritesEvent.EditFailed)
                }
            }
        }

        private fun putBack(entry: LibraryEntry) {
            val index = removedAt.remove(entry.id) ?: return
            if (index < 0) return
            updateShelf { shelf -> shelf.copy(entries = shelf.entries.insertedAt(index, entry)) }
        }

        private inline fun updateShelf(transform: (FavoriteShelf) -> FavoriteShelf) {
            val current = mutableState.value as? FavoritesGridUiState.Success ?: return
            mutableState.value = current.copy(shelf = transform(current.shelf))
        }
    }

/** The route's wire value back to a type. Anything unexpected falls back to anime rather than crashing. */
internal fun mediaTypeOf(wire: String): MediaType =
    MediaType.entries.find { it.name.equals(wire, ignoreCase = true) } ?: MediaType.ANIME

internal fun MediaType.wire(): String = name.lowercase()
