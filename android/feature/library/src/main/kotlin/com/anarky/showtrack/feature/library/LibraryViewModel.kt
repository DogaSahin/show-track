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

        // `loading` and `hasLoaded` folded into one state object rather than two separate
        // MutableStateFlows: `combine` only has typed overloads up to five flows, and this class
        // already uses all five (repository.observeLibrary(), loading/hasLoaded, loadingMore,
        // error, loadMoreError) — a sixth would force the vararg/Array overload, which loses the
        // per-flow types and turns the lambda's parameters into `Any?`. Folding avoids that
        // without reaching for a bespoke combine helper.
        //
        // `loading`: whether a full reload (init, a tab switch, a sort change, or a retry) is in
        // flight. Starts true: `init` below kicks one off before this class finishes
        // constructing, and a collector that subscribes to `state` before that completes must see
        // Loading, not a misleadingly empty Success.
        //
        // `hasLoaded`: whether THE REPOSITORY HAS ANSWERED SUCCESSFULLY AT ALL, this session —
        // a full reload (any filter) OR a `loadMore()` page fetch, not "a full reload" alone.
        // Starts false, flips true in `guard`'s success branch UNCONDITIONALLY — not gated by
        // `trackLoading` — and is NEVER reset back to false afterwards, not even by
        // `selectStatus`/`selectSort`. Two things had to be learned the hard way to land on this:
        //
        // 1. A per-selection reset ("a freshly picked filter hasn't loaded ANYTHING yet") looks
        //    appealing but is wrong: `LibraryRepositoryImpl.applyFilter` reverts its OWN internal
        //    filter on a throw, so a FAILED switch back to the default tab, after some OTHER
        //    filter had already loaded successfully, leaves `observeLibrary()` still keyed to
        //    that other (reverted-to) filter — `entries` is that other filter's LIVE rows, not
        //    the cache. A reset here would clear `hasLoaded` for that switch and let those rows
        //    render as `Success(isStale = true)` under the wrong tab.
        // 2. Gating the flip on `trackLoading` (so only a full reload counts, never a `loadMore`)
        //    also looks appealing but is wrong for a different reason: `CursorPaginator.loadMore`
        //    mutates `_items` on success exactly like `restart()` does, and a STALE render's own
        //    list auto-fires `loadMore()` the moment its last cached row is visible
        //    (`LibraryList`'s `LaunchedEffect(shouldLoadMore)`). A transient failure followed by
        //    the network answering THAT call would otherwise leave `hasLoaded` false forever and
        //    the "Showing saved titles" banner sitting over rows `loadMore` just fetched live.
        //
        // So `hasLoaded` genuinely means "has the network answered this session", not "have the
        // cached rows on screen been superseded" — those are NOT the same claim. This ViewModel
        // cannot tell the difference between a fetch that repopulated `entries` and one that
        // didn't touch them at all; it only knows the repository stopped throwing. **Known
        // residual, not closed by this ViewModel:** `LibraryRepositoryImpl` is `@Singleton`, and
        // its `add()` (called from `SearchViewModel`/`DetailViewModel`, both outside this module)
        // also calls `refresh()` — a success there is invisible here entirely, so a title added
        // from Search while this screen sits stale leaves `isStale` mislabelling whatever
        // `observeLibrary()` next emits. Closing that needs a signal FROM `:core:data` about
        // which branch `observeLibrary()` actually took (cache vs. paged), which is a bigger
        // change than this task took on — see [state]'s KDoc for where that signal would plug in.
        // `hasLoaded` is what lets [state] tell "the network has never once answered, this
        // session" (fall back to cache) apart from "it has answered before, and THIS attempt
        // failed" (show the error, not a lie about freshness) — see [state]'s KDoc for the full
        // precedence this drives.
        private val mutableLoadState = MutableStateFlow(LoadState(loading = true, hasLoaded = false))

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
         * screen is genuinely gone. `mutableLoadState`, `mutableLoadingMore`, `mutableError` and
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
         * [mutableError] takes priority over [mutableLoadState]'s `loading` in the `when` below on
         * purpose: a reload that just failed always finishes by flipping `loading` back to
         * `false` (see [guard]), and if a stale `Error` outranked a fresh `false` loading flag the
         * screen would flash back to the OLD error for one frame before the new attempt's
         * `Loading` (or a genuine failure) took over. `applyCurrentFilter(blank = true)` clears
         * [mutableError] itself, before it ever launches a coroutine, which is what actually
         * prevents that flash for a retry FROM A GENUINE [LibraryUiState.Error] (a tab switch
         * always passes `blank = true` too, for its own, structural reason — see
         * [selectStatus]'s KDoc) — the ordering in `guard` is a second line of defence, not the
         * fix. A retry from the `isStale = true` cache-fallback shape below is the one case that
         * does NOT blank at all — see [LibraryViewModel.refresh]'s KDoc for why leaving
         * [mutableError] untouched there is itself correct rather than an oversight this
         * paragraph's reasoning would otherwise call out.
         *
         * Decision C-B, made real: an error wins UNLESS this is the default filter, the network
         * has never once answered this session ([LoadState.hasLoaded] is false), and there are
         * `entries` to show — in which case `Success(isStale = true)` renders instead. Both
         * conditions are load-bearing, not belt-and-braces:
         *
         * - Dropping `hasLoaded` would mean a reload that fails AFTER a genuine success (for ANY
         *   filter, this session — see [mutableLoadState]'s KDoc for why it is session-scoped, not
         *   per-selection) falls back to whatever [entries] currently holds instead of telling the
         *   user the refresh failed — silently lying about freshness, and (before the app has ever
         *   had one success at all) potentially showing the WRONG filter's rows under the current
         *   tab, not just outdated ones.
         * - Dropping the `isDefault` check (via [mutableFilter], read directly here rather than
         *   folded into the `combine` — a UI-facing selection with no upstream of its own, see
         *   [filter]'s KDoc) would hit the stale-rows trap on the app's very first load: a FAILED
         *   switch away from the default filter, before anything has ever succeeded, still has
         *   `entries` holding whatever `observeLibrary()` last emitted for the default view (the
         *   cache, most likely) and `hasLoaded` still false. Without this check those rows would
         *   render, silently, under the new filter's tab.
         *
         * **`isStale` is honest about "the network hasn't answered", not about "these rows came
         * from the cache" — those are different claims, and this ViewModel cannot tell them apart
         * on its own.** See [mutableLoadState]'s KDoc for the one known residual this leaves open
         * (`LibraryRepositoryImpl.add()`, called from OTHER screens, succeeding invisibly to this
         * one) and the `:core:data`-side signal that would close it, deferred rather than grown
         * here.
         *
         * Reading `mutableFilter.value` directly here (rather than as a sixth `combine` source) is
         * safe because every path that changes it — [selectStatus], [selectSort] — synchronously
         * follows that write with a write to [mutableLoadState] or [mutableError] before this
         * lambda can next run, so the value this lambda observes is never stale relative to the
         * `combine` emission that triggered it.
         */
        val state: StateFlow<LibraryUiState> =
            combine(
                repository.observeLibrary(),
                mutableLoadState,
                mutableLoadingMore,
                mutableError,
                mutableLoadMoreError,
            ) { entries, loadState, loadingMore, error, pageError ->
                val showCacheInstead =
                    error != null &&
                        mutableFilter.value.isDefault &&
                        !loadState.hasLoaded &&
                        entries.isNotEmpty()
                when {
                    // pageError carried through (Finding 4, review round 2): a stale render's
                    // list auto-fires loadMore() just like a live one, and a failure there must
                    // surface here too - otherwise it is silently swallowed, no footer, no retry.
                    showCacheInstead ->
                        LibraryUiState.Success(
                            entries = entries,
                            loadingMore = loadingMore,
                            pageError = pageError,
                            isStale = true,
                        )
                    error != null -> LibraryUiState.Error(error)
                    loadState.loading -> LibraryUiState.Loading
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

        // Neither of these touches `mutableLoadState.hasLoaded` — see its KDoc for why resetting
        // it per selection would re-open the stale-rows trap from the one direction `isDefault`
        // cannot guard.
        //
        // `blank = true`, always — not merely "the current default", but structurally required:
        // [state]'s `combine` does not carry [mutableFilter] as a source at all (read directly in
        // the lambda instead — see [state]'s KDoc on why that is safe), so changing the filter
        // ALONE would never trigger a re-evaluation of that lambda. `applyCurrentFilter(blank =
        // true)` is what forces one, by writing to [mutableError]/[mutableLoadState], both of
        // which the `combine` DOES watch. Passing `false` here would not just show the old tab's
        // rows under the new header (the walkthrough 12 bug) — with nothing left to force a
        // recombination, the tab row would visibly select PLANNED while the screen kept
        // rendering whatever it last rendered, indefinitely, until something UNRELATED happened
        // to touch one of the real combine sources.
        fun selectStatus(status: UserMediaStatus?) {
            mutableFilter.value = mutableFilter.value.copy(status = status)
            applyCurrentFilter(blank = true)
        }

        fun selectSort(sort: LibrarySort) {
            mutableFilter.value = mutableFilter.value.copy(sort = sort)
            applyCurrentFilter(blank = true)
        }

        /**
         * Re-fetches under whatever [filter] currently holds — the initial load, and a retry.
         * `ErrorState`'s and `StaleDataBanner`'s Retry buttons are both wired to this exact
         * function, undifferentiated (`LibraryScreen.kt`'s `onRetry = viewModel::refresh`), so
         * this function is the only place that CAN tell the two apart, and it does so from
         * [state] rather than from the caller — there is nothing at the call site to
         * parameterise with, since both buttons call the identical no-arg function.
         *
         * `blank = `[state]`.value !is `[LibraryUiState.Success]. A genuine [LibraryUiState.Error]
         * (nothing worth keeping on screen) still blanks to [LibraryUiState.Loading] immediately,
         * same as before this fix — see the retry-from-a-real-error test, unchanged. A
         * [LibraryUiState.Success] — INCLUDING the `isStale = true` cache-fallback shape decision
         * C-B renders — does not: [applyCurrentFilter]`(blank = false)` leaves [mutableError] and
         * [mutableLoadState] exactly as they are, so the `combine` in [state] does not re-emit at
         * all until the retry actually resolves, and the rows plus the stale banner stay on
         * screen, unchanged, for the whole round trip. Before this fix, `refresh()` blanked
         * unconditionally, so tapping Retry on the offline-cold-start banner replaced it with a
         * full-screen spinner and then, on a hung connection, nothing at all until the socket
         * timed out — walkthrough 11 step 4 in the README documents the fixed behaviour.
         *
         * Reading `state.value` here — rather than threading a boolean through from the UI, the
         * way [selectStatus]/[selectSort] pass `blank = true` — is safe for the same reason
         * reading [mutableFilter]`.value` directly in [state]'s `combine` lambda is (see that
         * KDoc): `refresh()` is only ever invoked from composition that is already collecting
         * [state] (`collectAsStateWithLifecycle`), so `.value` reflects the latest render rather
         * than [state]'s `initialValue`. A `refresh()` called with nothing collecting (a bare test
         * that never subscribes) reads `initialValue` (`Loading`) and blanks — the same
         * conservative fallback [state]'s own `WhileSubscribed` gate already assumes elsewhere in
         * this class.
         */
        fun refresh() = applyCurrentFilter(blank = state.value !is LibraryUiState.Success)

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
         * [mutableError] and [mutableLoadState]'s `loading` are cleared/set HERE, synchronously,
         * before the fetch is even launched — not left to `guard`'s success path to clear later —
         * but only when [blank] is true. A previous version of this function did this
         * unconditionally, on the theory that ANY reload should blank; that theory is right for
         * [selectStatus]/[selectSort] (see their KDoc: they have no other way to force a
         * recombination at all) and wrong for [refresh]'s retry-from-a-stale-cache case, where
         * blanking synchronously replaced the cached rows and the `StaleDataBanner` with a
         * full-screen spinner for the whole round trip — on a hung connection, with nothing on
         * screen until the socket timed out. [refresh] is the only caller that ever passes
         * `blank = false`, and only when [state] already holds a [LibraryUiState.Success] worth
         * keeping — see its KDoc for the exact condition and for why leaving [mutableError] and
         * [mutableLoadState] untouched in that branch is itself the fix: with neither combine
         * source touched, [state] simply does not re-emit until the retry resolves, which is
         * indistinguishable, from the screen's point of view, from "the round trip changed
         * nothing yet."
         *
         * Before this file had a `blank` parameter at all, an even earlier version of this
         * function only set the loading flag and left the stale `mutableError` in place until the
         * fetch resolved; since `error != null` outranks `loading` in [state]'s `when`, that meant
         * a retry (or a tab switch made while already showing an error) displayed the IDENTICAL,
         * now-stale `ErrorState` for the entire round trip instead of `Loading` — worse than doing
         * nothing, since it looked like the retry had been silently ignored. That failure mode is
         * why `blank = true` clears [mutableError] BEFORE setting `loading`, not after.
         *
         * [mutableLoadMoreError] is cleared unconditionally, [blank] or not: it describes a
         * page-fetch failure on the list this call is about to REPLACE via a full reload either
         * way, and would otherwise survive as a stale footer message on a list it was never about.
         * Clearing it does not blank the whole screen — it only ever fed a footer, never [state]'s
         * top-level branch — so it carries none of the risk the rest of this function's blanking
         * does.
         */
        private fun applyCurrentFilter(blank: Boolean) {
            if (blank) {
                mutableError.value = null
                mutableLoadState.value = mutableLoadState.value.copy(loading = true)
            }
            mutableLoadMoreError.value = null
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
         * [trackLoading] is per-CALL, not a class-wide flag, and it gates ONLY `loading` — not
         * `hasLoaded` (see below): [loadMore] also runs through this function and DOES flip
         * `hasLoaded` on success, but must never touch `loading`. Both a filter change and a page
         * fetch can be in flight at once only in theory (the UI never shows a scrollable list
         * while [mutableLoadState]'s `loading` is true), but nothing here should rely on the UI to
         * keep that promise — if `finally` unconditionally cleared it, a `loadMore()` coroutine
         * that happens to resolve before a concurrent filter change's fetch would flip the
         * full-screen spinner off while that reload is still genuinely in flight.
         *
         * `hasLoaded` is set true on the success path ONLY — never in `catch` — but,
         * deliberately, is NOT gated by `trackLoading` the way clearing `loading` in `finally` is:
         * a successful [loadMore] counts as "the network answered" just as much as a successful
         * [applyCurrentFilter] does (see [mutableLoadState]'s KDoc for why gating it on
         * `trackLoading` was itself a bug). That success/failure asymmetry — set on success, never
         * touched on failure — is what [state] relies on to fall back to the cache on a failure
         * that has NEVER been preceded by ANY success this session (`hasLoaded` still false),
         * while a failure AFTER a real success leaves `hasLoaded = true` untouched and [state]
         * shows the error instead — see [state]'s KDoc.
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
                    // Unconditional, NOT gated by trackLoading: a successful loadMore() writes
                    // CursorPaginator's `_items` exactly like a successful full reload does (see
                    // CursorPaginator.loadMore), so "the repository has answered at least once
                    // this session" is true the moment EITHER succeeds, not only a full reload.
                    // Gating this on trackLoading was Finding 3 (review round 2): a stale render's
                    // auto-fired loadMore() could succeed without ever closing the cache fallback,
                    // leaving the "Showing saved titles" banner over rows loadMore had just
                    // fetched live.
                    mutableLoadState.value = mutableLoadState.value.copy(hasLoaded = true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    errorSink.value = failure
                } finally {
                    if (trackLoading) mutableLoadState.value = mutableLoadState.value.copy(loading = false)
                }
            }
        }

        /** See [mutableLoadState]'s KDoc for why `loading` and `hasLoaded` are folded together. */
        private data class LoadState(
            val loading: Boolean,
            val hasLoaded: Boolean,
        )

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
