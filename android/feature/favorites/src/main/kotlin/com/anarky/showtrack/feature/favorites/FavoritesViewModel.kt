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
 *
 * **No `init { refresh() }`** (review finding, round 2 — an earlier version of this class had
 * one). `FavoritesScreen`'s `LifecycleResumeEffect` already fires on the very first composition,
 * not only a later resume: `Lifecycle` replays `ON_CREATE`/`ON_START`/`ON_RESUME` to a
 * freshly-registered observer when the `Lifecycle` it is attached to is already resumed by the
 * time the effect enters composition (`FavoritesResumeTest` measured this directly). An `init`
 * block here would therefore not be covering some gap the resume effect misses — it would be a
 * SECOND, redundant `GET /v1/library?favorite=true` on every first open. `ProfileViewModel` keeps
 * its own `init` alongside the identical resume effect for the opposite reason stated in ITS own
 * KDoc: its `refresh()` is a synchronous `PackageManager` read, so a duplicate call there costs
 * nothing — `refresh()` here is a real network round trip, so the duplicate is not free and is
 * worth removing.
 */
@HiltViewModel
class FavoritesViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
        val state: StateFlow<FavoritesUiState> = mutableState.asStateFlow()

        /**
         * The only operation allowed to replace [state] with [FavoritesUiState.Error] wholesale —
         * see [FavoritesUiState]'s KDoc for why that is safe here. Called from the initial resume
         * (there is no `init` — see this class's own KDoc) and from [FavoritesUiState.Error]'s
         * retry action.
         *
         * [FavoritesUiState.Loading] is written wholesale ONLY when [state] is not already
         * [FavoritesUiState.Success] (review finding, round 2). Writing it unconditionally — the
         * previous version of this function did — is exactly right for the first load and for a
         * retry from [FavoritesUiState.Error] (decision C-S: clear the error before launching a
         * retry, not only on success, the same discipline `DiscoverViewModel.refresh` follows),
         * but is wrong for the case this function exists to serve on every OTHER call: a resume
         * over an already-populated screen. [repository.refreshFavorites] is a real network round
         * trip in production (unlike the non-suspending fakes this class's own tests originally
         * used, which is why this bug shipped unnoticed) — blanking a populated list to a
         * full-screen spinner for that round trip on every Favorites -> Detail -> Back, or every
         * tab switch back to Favorites, also `restart()`s the paginator (dropping pages 2..n) and
         * throws away `FavoritesList`'s `rememberLazyListState()` (`FavoritesList` leaves
         * composition while `Loading` renders), resetting scroll position — the EXACT class of bug
         * this screen's own paginator was given its own instance to avoid (this class's own KDoc,
         * task 9b.4's brief), reintroduced here by a different route. A resume over [Success] is
         * now a silent re-fetch that swaps `entries` in place once it lands, with the stale list
         * still on screen for the round trip's duration.
         *
         * This is also the acceptance path for "unfavouriting elsewhere removes the entry from
         * this view": [repository.refreshFavorites] re-fetches `favorite=true` from the server, so
         * a title unfavourited from Detail or Library simply stops coming back the next time this
         * runs — there is no separate reconciliation step needed.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            if (mutableState.value !is FavoritesUiState.Success) {
                mutableState.value = FavoritesUiState.Loading
            }
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
