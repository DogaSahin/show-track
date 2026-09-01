package com.anarky.showtrack.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.UserMediaStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The first real consumer of the app graph, and the whole point of it: the constructor names an
 * INTERFACE from `:core:data` and nothing else. Retrofit, Room, the DTOs and the entities are all
 * `implementation`-scoped inside that module, so this module could not name them if it tried —
 * architecture rule 2 enforced by the compile classpath rather than by review.
 *
 * No use-case layer between this and the repository (owner's standing guidance): a use case per
 * method would be one class each forwarding a single call.
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        /**
         * The tab row's selection, and the sort in effect. Deliberately its OWN `StateFlow`
         * outside [LibraryUiState] rather than a field on [LibraryUiState.Success]: every filter
         * change reloads the list, which means `Success` is briefly gone (replaced by `Loading`)
         * on every tab tap. A selection that lived inside `Success` would vanish for that same
         * window and the tab row would visibly snap back to "All" on every switch.
         *
         * It is also never reverted on a failed [applyCurrentFilter] — see that function's KDoc
         * for why matching [LibraryRepository]'s own revert-on-failure behaviour here would be
         * the wrong call.
         */
        private val mutableFilter = MutableStateFlow(LibraryFilter())
        val filter: StateFlow<LibraryFilter> = mutableFilter.asStateFlow()

        // Whether a full reload (init, a tab switch, a sort change, or a retry) is in flight.
        // Starts true: `init` below kicks one off before this class finishes constructing, and a
        // collector that subscribes to `state` before that completes must see Loading, not a
        // misleadingly empty Success.
        private val mutableLoading = MutableStateFlow(true)

        // Whether a `loadMore()` page fetch is in flight — a footer spinner under an otherwise
        // complete list, never a reason to blank the screen, hence it is a field on `Success`
        // rather than a fourth branch of `mutableLoading`.
        private val mutableLoadingMore = MutableStateFlow(false)

        // The last failure from a full reload or a `loadMore()`, or null. See `guard` below for
        // why an exception can never be allowed to just propagate out of `viewModelScope.launch`.
        private val mutableError = MutableStateFlow<Throwable?>(null)

        /**
         * `SharingStarted.Eagerly`, not `WhileSubscribed` — a deliberate departure from the
         * previous `entries` field this replaces, and worth spelling out since it is a real
         * behaviour change, not a formatting choice.
         *
         * [mutableLoading], [mutableLoadingMore] and [mutableError] are all written
         * unconditionally by [guard]'s `viewModelScope.launch`, regardless of whether anything is
         * collecting [state] — a background refresh still resolves to a captured error even with
         * no screen watching. Gating only the [LibraryRepository.observeLibrary] slice of this
         * `combine` behind `WhileSubscribed` would make three of its four inputs live at all
         * times and the fourth freeze the moment the last collector goes away — and, worse, would
         * leave [state] itself unusable via a bare `.value` read (its own subscription is what
         * would start the upstream), unlike [filter] and every other `StateFlow` on this class.
         * `Eagerly` keeps all four inputs on the same footing.
         *
         * The trade-off this gives up: the ORIGINAL `entries` field stopped the Room+paginator
         * observer a few seconds after the screen left composition, specifically so a backgrounded
         * screen was not holding a live DB cursor open indefinitely. With `Eagerly`, this
         * ViewModel's observer runs for [viewModelScope]'s whole lifetime — bounded by the screen
         * leaving the back stack (Hilt scopes a `hiltViewModel()` instance to its
         * `NavBackStackEntry`), not by the screen merely being backgrounded. Room's `Flow` is a
         * change-notification callback rather than a poll, so the ongoing cost while backgrounded
         * is small, but it is not zero, and this is the reason: correctness and testability of
         * [state] as a single source of truth won out over that saving.
         *
         * [mutableError] takes priority over [mutableLoading] in the `when` below on purpose: a
         * reload that just failed always finishes by flipping `mutableLoading` back to `false`
         * (see [guard]), and if a stale `Error` outranked a fresh `false` loading flag the screen
         * would flash back to the OLD error for one frame before the new attempt's `Loading` (or
         * a genuine failure) took over. `guard` clears [mutableError] before it ever clears
         * [mutableLoading], so the two flags are never simultaneously "just failed" and "not
         * loading" in a way this ordering could expose — but relying on write order across two
         * independent `StateFlow`s is fragile, so the `when` below hard-codes the same priority
         * as a second line of defence.
         */
        val state: StateFlow<LibraryUiState> =
            combine(
                repository.observeLibrary(),
                mutableLoading,
                mutableLoadingMore,
                mutableError,
            ) { entries, loading, loadingMore, error ->
                when {
                    error != null -> LibraryUiState.Error(error)
                    loading -> LibraryUiState.Loading
                    else -> LibraryUiState.Success(entries = entries, loadingMore = loadingMore)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LibraryUiState.Loading,
            )

        init {
            refresh()
        }

        fun selectStatus(status: UserMediaStatus?) {
            mutableFilter.value = mutableFilter.value.copy(status = status)
            applyCurrentFilter()
        }

        fun selectSort(sort: LibrarySort) {
            mutableFilter.value = mutableFilter.value.copy(sort = sort)
            applyCurrentFilter()
        }

        /** Re-fetches under whatever [filter] currently holds — the initial load, and a retry. */
        fun refresh() = applyCurrentFilter()

        /**
         * Re-entrant calls are dropped up front rather than left to [LibraryRepository]'s own
         * mutex: a `LazyColumn`'s end-reached callback fires on every frame near the bottom, and
         * without this check every one of those frames would launch its own coroutine and queue
         * up behind [com.anarky.showtrack.core.data.paging.CursorPaginator]'s lock — safe, but a
         * backlog of no-op requests that delays the next PAGE the user actually scrolls to. The
         * flag is set synchronously, before `guard` even schedules a coroutine, so two calls made
         * back to back on the same frame cannot both pass the check before either one flips it.
         */
        fun loadMore() {
            if (mutableLoadingMore.value) return
            mutableLoadingMore.value = true
            guard(trackLoading = false) {
                try {
                    repository.loadMore()
                } finally {
                    mutableLoadingMore.value = false
                }
            }
        }

        /**
         * [mutableFilter] is set in [selectStatus]/[selectSort] BEFORE this runs, and stays set
         * even if the fetch below throws — unlike `LibraryRepositoryImpl.applyFilter`, which
         * reverts its OWN internal filter on failure to keep it in agreement with what
         * `CursorPaginator` actually holds (task 9a.5's carried-forward note). That invariant is
         * about the repository's cursor/page state; it says nothing about what the tab row should
         * show. Reverting [mutableFilter] here too would snap the selected tab back to whatever
         * it was before the tap, underneath an error message that never explains the tab moved —
         * indistinguishable from the tap being silently ignored. So a failed switch leaves the
         * user's chosen tab selected and an [LibraryUiState.Error] underneath it; retrying calls
         * this again with the SAME [mutableFilter] value, which is exactly what should happen.
         */
        private fun applyCurrentFilter() {
            mutableLoading.value = true
            guard(trackLoading = true) { repository.applyFilter(mutableFilter.value) }
        }

        /**
         * `try`/`catch(Exception)` and not `runCatching`: runCatching swallows
         * [CancellationException] as well, which is structured concurrency's own control flow —
         * a cancelled child that eats its cancellation stops the parent from ever completing.
         * Catching Exception rather than Throwable leaves Errors (OOM, StackOverflow) alone,
         * which are not something a screen can recover from.
         *
         * [mutableError] is resolved to its final value (cleared on success, set on failure)
         * BEFORE [mutableLoading] is flipped back to `false` in `finally` — see [state]'s KDoc for
         * why that ordering, not just the values themselves, is what keeps a retry from flashing
         * the previous failure for a frame.
         *
         * [trackLoading] is per-CALL, not a class-wide flag: [loadMore] also runs through this
         * function but must never touch [mutableLoading]. Both a filter change and a page fetch
         * can be in flight at once only in theory (the UI never shows a scrollable list while
         * [mutableLoading] is true), but nothing here should rely on the UI to keep that promise —
         * if `finally` unconditionally cleared [mutableLoading], a `loadMore()` coroutine that
         * happens to resolve before a concurrent filter change's fetch would flip the full-screen
         * spinner off while that reload is still genuinely in flight.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun guard(
            trackLoading: Boolean = false,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    block()
                    mutableError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableError.value = failure
                } finally {
                    if (trackLoading) mutableLoading.value = false
                }
            }
        }
    }
