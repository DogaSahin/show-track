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

        // The last failure from a full reload (init, a tab/sort change, or a retry), or null.
        // Deliberately a SEPARATE slot from `mutableLoadMoreError` below — see `guard`'s KDoc for
        // why sharing one slot between the two was the actual bug a previous version of this
        // class had: a full reload's failure and a page fetch's failure have very different
        // blast radii (one replaces the whole screen, the other survives underneath a footer),
        // and each one's SUCCESS must only ever clear its own failure, never the other one's.
        private val mutableError = MutableStateFlow<Throwable?>(null)

        // The last failure from a `loadMore()` page fetch, or null. Feeds `Success.pageError`
        // only — it never promotes `state` to `Error`, because the entries a failed page fetch
        // left behind are still valid and still on screen (`LibraryRepository.loadMore` leaves
        // `paginator.items` untouched on a throw; see `LibraryUiState.Success.pageError`'s KDoc).
        private val mutableLoadMoreError = MutableStateFlow<Throwable?>(null)

        /**
         * `WhileSubscribed(5_000)`, matching the `entries` field this replaced in an earlier
         * revision of this class: [LibraryRepository.observeLibrary] combines a Room-backed flow,
         * and holding that open for this ViewModel's entire lifetime — rather than only while
         * something is actually watching — means Room's `InvalidationTracker` keeps re-running the
         * query, re-mapping every row and rebuilding this whole `combine` on every write to the
         * library table (the sync and airing jobs both do this), for a screen nobody is looking
         * at. `WhileSubscribed(5_000)` still bridges a configuration change — which would
         * otherwise restart the combine and blink the list — without paying that cost once the
         * screen is genuinely gone. `mutableLoading`, `mutableLoadingMore`, `mutableError` and
         * `mutableLoadMoreError` are cheap, subscription-less `MutableStateFlow`s with no upstream
         * of their own; only the Room-backed source actually benefits from — and needs — the gate.
         *
         * A consequence worth knowing when testing (or otherwise reading) this class: [state]'s
         * `.value` will not advance past `initialValue` unless something is actively collecting
         * it — `collectAsStateWithLifecycle` in production, `Turbine`'s `.test { }` or
         * `backgroundScope.launch { state.collect {} }` in a test. That is a property of
         * `WhileSubscribed` itself, not a reason to abandon it for something that is always live —
         * every other `StateFlow` on this ViewModel (`filter` included) is a plain, subscription-
         * less `MutableStateFlow`, so this is the one exception, and it is the one exception on
         * purpose.
         *
         * [mutableError] takes priority over [mutableLoading] in the `when` below on purpose: a
         * reload that just failed always finishes by flipping `mutableLoading` back to `false`
         * (see [guard]), and if a stale `Error` outranked a fresh `false` loading flag the screen
         * would flash back to the OLD error for one frame before the new attempt's `Loading` (or
         * a genuine failure) took over. [applyCurrentFilter] clears [mutableError] itself, before
         * it ever launches a coroutine, which is what actually prevents that flash (a retry no
         * longer shows the stale error for the round trip's whole duration — see
         * [applyCurrentFilter]'s KDoc) — the ordering in `guard` is a second line of defence, not
         * the fix.
         */
        val state: StateFlow<LibraryUiState> =
            combine(
                repository.observeLibrary(),
                mutableLoading,
                mutableLoadingMore,
                mutableError,
                mutableLoadMoreError,
            ) { entries, loading, loadingMore, error, pageError ->
                when {
                    error != null -> LibraryUiState.Error(error)
                    loading -> LibraryUiState.Loading
                    else -> LibraryUiState.Success(entries = entries, loadingMore = loadingMore, pageError = pageError)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
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
         *
         * Routed through [mutableLoadMoreError], never [mutableError]: a failed page fetch must
         * leave the currently-loaded [LibraryUiState.Success] standing, not blow the screen away
         * — see [LibraryUiState.Success.pageError]'s KDoc.
         */
        fun loadMore() {
            if (mutableLoadingMore.value) return
            mutableLoadingMore.value = true
            guard(errorSink = mutableLoadMoreError) {
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
         *
         * [mutableError] and [mutableLoadMoreError] are both cleared HERE, synchronously, before
         * [mutableLoading] is even set — not left to `guard`'s success path to clear later. A
         * previous version of this function only set `mutableLoading = true` and left the stale
         * `mutableError` in place until the fetch resolved; since `error != null` outranks
         * `loading` in [state]'s `when`, that meant a retry (or a tab switch made while already
         * showing an error) displayed the IDENTICAL, now-stale `ErrorState` for the entire round
         * trip instead of `Loading` — worse than doing nothing, since it looked like the retry had
         * been silently ignored. [mutableLoadMoreError] is cleared too: it describes a page-fetch
         * failure on the list this call is about to REPLACE, and would otherwise survive as a
         * stale footer message on a list it was never about.
         */
        private fun applyCurrentFilter() {
            mutableError.value = null
            mutableLoadMoreError.value = null
            mutableLoading.value = true
            guard(errorSink = mutableError, trackLoading = true) { repository.applyFilter(mutableFilter.value) }
        }

        /**
         * `try`/`catch(Exception)` and not `runCatching`: runCatching swallows
         * [CancellationException] as well, which is structured concurrency's own control flow —
         * a cancelled child that eats its cancellation stops the parent from ever completing.
         * Catching Exception rather than Throwable leaves Errors (OOM, StackOverflow) alone,
         * which are not something a screen can recover from.
         *
         * [errorSink] is which of [mutableError] / [mutableLoadMoreError] this particular call
         * writes to — a full reload ([applyCurrentFilter]) and a page fetch ([loadMore]) share
         * this function's try/catch shape but must NEVER share a slot: a slot shared between them
         * means a page fetch's SUCCESS silently clears a full reload's error (the inverse of the
         * bug above — a stale `ErrorState` disappearing behind the user's back for a reason
         * unrelated to what actually failed), and a page fetch's FAILURE promotes `state` all the
         * way to `Error`, discarding a fully-loaded list over one failed next page. Passing the
         * sink in per call, rather than writing to a hard-coded field, is what keeps the two
         * failure channels genuinely independent instead of merely "usually fine".
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
            errorSink: MutableStateFlow<Throwable?>,
            trackLoading: Boolean = false,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    block()
                    errorSink.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    errorSink.value = failure
                } finally {
                    if (trackLoading) mutableLoading.value = false
                }
            }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
