package com.anarky.showtrack.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The favourites screen (task 9b.4, decision D-H).
 *
 * The constructor names ONE interface from `:core:data` — architecture rule 2, structural rather
 * than a review item, the same shape `LibraryViewModel`/`DiscoverViewModel` use.
 *
 * **`state` is a plain [MutableStateFlow], not `combine(...).stateIn(WhileSubscribed(5_000))` the
 * way `LibraryViewModel.state` is** (decision C-U). `WhileSubscribed` exists on the library screen
 * to stop a continuously-updating, Room-backed [kotlinx.coroutines.flow.Flow] from being
 * re-collected (and its query re-run) for a screen nobody is watching. Nothing here is like that:
 * [LibraryRepository.favoriteEntries] has no Room-backed upstream at all — it is backed by its own
 * network-only `CursorPaginator` (see [LibraryRepository.favoriteEntries]'s own KDoc) — so there is
 * no independent background writer to gate a subscription against, and [refresh]/[loadMore] are
 * one-shot suspend calls THIS ViewModel drives itself, exactly `DiscoverViewModel`'s shape rather
 * than `LibraryViewModel`'s.
 *
 * **Two failure channels, not one** (decision C-S) — see [FavoritesUiState]'s KDoc: a failed
 * [refresh] may replace the whole screen with [FavoritesUiState.Error]; a failed [loadMore] must
 * leave [FavoritesUiState.Success.entries] standing and surface beside the list instead.
 *
 * No `add` here, unlike `DiscoverViewModel` — favouriting happens on Detail or Library, and this
 * screen only ever reflects it, on the next [refresh].
 */
@HiltViewModel
class FavoritesViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
        val state: StateFlow<FavoritesUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /**
         * The initial load, and the only operation allowed to replace [state] with
         * [FavoritesUiState.Error] wholesale — see [FavoritesUiState]'s KDoc for why that is safe
         * here. [FavoritesUiState.Loading] is written SYNCHRONOUSLY before the coroutine is even
         * launched (decision C-S: clear the error before launching a retry, not only on success),
         * so a retry from [FavoritesUiState.Error] does not leave the OLD error on screen for the
         * round trip's whole duration — `DiscoverViewModel.refresh`'s same discipline.
         *
         * This is also the acceptance path for "unfavouriting elsewhere removes the entry from
         * this view": [repository.refreshFavorites] re-fetches `favorite=true` from the server, so
         * a title unfavourited from Detail or Library simply stops coming back the next time this
         * runs — there is no separate reconciliation step needed.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            mutableState.value = FavoritesUiState.Loading
            viewModelScope.launch {
                try {
                    repository.refreshFavorites()
                    mutableState.value = FavoritesUiState.Success(entries = repository.favoriteEntries.value)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.value = FavoritesUiState.Error(failure)
                }
            }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore`/
         * `DiscoverViewModel.loadMore` use: a `LazyColumn`'s end-reached callback fires on every
         * frame near the bottom, and without this a scroll near the bottom would queue up a fetch
         * per frame.
         *
         * Routed through [FavoritesUiState.Success.pageError], never [FavoritesUiState.Error]: the
         * rows a failed page-2 fetch left behind are still valid and still on screen.
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            if (current.loadingMore) return
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    repository.loadMoreFavorites()
                    replaceSuccess {
                        it.copy(entries = repository.favoriteEntries.value, loadingMore = false, pageError = null)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(loadingMore = false, pageError = failure) }
                }
            }
        }

        private inline fun replaceSuccess(transform: (FavoritesUiState.Success) -> FavoritesUiState.Success) {
            val latest = mutableState.value as? FavoritesUiState.Success ?: return
            mutableState.value = transform(latest)
        }
    }
