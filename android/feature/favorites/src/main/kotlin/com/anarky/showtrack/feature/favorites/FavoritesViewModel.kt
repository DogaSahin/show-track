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
 * [refresh] may replace the whole screen with [FavoritesUiState.Error], OR mark a populated screen
 * [FavoritesUiState.Success.isStale] instead of destroying it — see [refresh]'s own KDoc for which
 * and why. A failed [loadMore] must leave [FavoritesUiState.Success.entries] standing and surface
 * beside the list instead, in either case.
 *
 * No `add` here, unlike `DiscoverViewModel` — favouriting happens on Detail or Library, and this
 * screen only ever reflects it, on the next [refresh].
 *
 * **[refresh] is guarded against re-entrancy (task 9c.8, E-M)**: `FavoritesScreen` wires the SAME
 * function to both `LifecycleResumeEffect` and `StaleDataBanner`/[FavoritesUiState.Error]'s retry
 * action, so a manual retry can land WHILE a resume-triggered fetch is still in flight. Before this
 * task neither call was guarded, so the two raced last-write-wins — a retry's response landing
 * before the resume's (or vice versa) could mark freshly-loaded data [FavoritesUiState.Success.isStale]
 * on top of a result that had already superseded it. [refreshInFlight] is a private, `state`-shape-
 * independent field, not a value scoped inside [FavoritesUiState.Success] — [DiscoverViewModel]'s
 * `addInFlight` field carries the identical reasoning: [refresh] can be called while [state] is
 * [FavoritesUiState.Loading] or [FavoritesUiState.Error] too, where there is no [FavoritesUiState.Success]
 * to scope a flag inside.
 *
 * **A dropped re-entrant call, not a coalesced one** (review finding M2, round 1): a second
 * [refresh] landing while [refreshInFlight] is `true` is discarded outright, not queued to run
 * again once the first finishes. Concretely: a slow resume-triggered [refresh] is still in flight,
 * the user goes to Detail, unfavourites a title, and returns — that second resume's [refresh] is
 * dropped, and the FIRST call's response (fetched before the unfavourite happened) is what renders,
 * with no automatic follow-up to correct it. `FeedViewModel.loadingGeneration` is the shape that
 * WOULD coalesce (a generation captured and re-checked, rather than a bare boolean) and was not
 * adopted here — [refresh] has no "subject" that changes under it the way a group switch does, so
 * the accepted cost is narrower: the NEXT resume is what corrects a dropped one, not this call.
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

        // See this class's own KDoc for why this exists and why it lives here rather than inside
        // FavoritesUiState.Success.
        private var refreshInFlight = false

        // Set when [loadMore] is dropped because a [refresh] is in flight, and drained by that
        // refresh's own `finally` (whole-branch fix round, BLOCKING 4) — `DiscoverViewModel`'s
        // identical mechanism, adopted here because this class had the identical hole: the guard
        // existed on ONE of the two functions that need it.
        //
        // Why a bare `if (refreshInFlight) return` in [loadMore] would not do: `EndOfListTrigger`
        // only emits on the false -> true edge of its own `shouldTrigger` (that composable's own
        // KDoc), and a dropped `loadMore()` changes neither the item count nor the scroll position,
        // so the trigger never re-fires on its own — paging would stop silently until the user
        // scrolled up past the threshold and back down. That is the project's dropped-call rule
        // ("acceptable only if something will re-issue it, or the user can see it was dropped")
        // failing both clauses at once.
        //
        // Cleared by [loadMore] itself, and only once that call has passed every early return and
        // is genuinely about to fetch — never unconditionally in [refresh]'s `finally`, which would
        // drop the same signal a second time if the re-issued call bailed out on its own
        // `loadingMore` guard (`DiscoverViewModel` round 3's own finding).
        private var pendingLoadMoreAfterRefresh = false

        /**
         * Called from the initial resume (there is no `init` — see this class's own KDoc) and from
         * [FavoritesUiState.Error]'s retry action.
         *
         * [FavoritesUiState.Loading] is written wholesale ONLY when [state] is not already
         * [FavoritesUiState.Success] (review finding, round 2). Writing it unconditionally — an
         * earlier version of this function did — is exactly right for the first load and for a
         * retry from [FavoritesUiState.Error] (decision C-S: clear the error before launching a
         * retry, not only on success, the same discipline `DiscoverViewModel.refresh` follows),
         * but is wrong for the case this function exists to serve on every OTHER call: a resume
         * over an already-populated screen. [repository.refreshFavorites] is a real network round
         * trip in production (unlike the non-suspending fakes this class's own tests originally
         * used, which is why this bug shipped unnoticed) — blanking a populated list to a
         * full-screen spinner for that round trip on every Favorites -> Detail -> Back, or every
         * tab switch back to Favorites, meant `FavoritesList` left composition while `Loading`
         * rendered, which recreated `rememberLazyListState()` and reset scroll position; that part
         * is fixed by this condition — `FavoritesList` now stays composed across a resume that
         * starts from [FavoritesUiState.Success], since [state] never passes through [FavoritesUiState.Loading]
         * to get there. **What this condition does NOT fix, and was never asked to (review finding,
         * round 3): `refreshFavorites()` still calls `CursorPaginator.restart()` under the hood,
         * which still drops pages 2..n on every resume.** A user paged to 60 entries and scrolled
         * to ~55 still lands on `Success(20)` when a resume's fetch succeeds — the SAME
         * `rememberLazyListState()` instance survives (unlike before this fix), but the DATA under
         * it shrinks, so the list clamps toward its own end, `EndOfListTrigger` immediately
         * re-fires, and the user ends up somewhere near index 19 after the ensuing `loadMore()`
         * calls rather than back at 55. Multi-page resume is a real, open design question (does a
         * resume re-fetch page 1 only, all previously-loaded pages, or nothing beyond a background
         * favourite/unfavourite diff?) that this task does not answer — this condition only fixes
         * the single-page case (composition survives; no data truncation to notice) and the
         * COMPOSITION-level reset for a multi-page one (no `LazyListState` recreation), not the
         * data-level one.
         *
         * A resume over [FavoritesUiState.Success] is now a silent re-fetch that swaps `entries` in
         * place once it lands, with the stale list still on screen for the round trip's duration.
         *
         * **On failure** (review finding, round 3): the `catch` re-reads [mutableState] rather than
         * trusting whether THIS call started from [FavoritesUiState.Success], so it also catches a
         * list a concurrent [loadMore] extended while this fetch was failing. If [state] is still a
         * [FavoritesUiState.Success] when the failure lands, this marks it
         * [FavoritesUiState.Success.isStale] instead of replacing it with [FavoritesUiState.Error] —
         * decision C-B's objection was never to showing older rows, only to showing them UNMARKED;
         * blanking a working, populated screen to a full-screen error over a background resume the
         * user never asked for would trade "possibly stale, marked" for "nothing, with a Retry
         * button that itself re-enters [FavoritesUiState.Loading]" — worse on both axes. The
         * full-screen [FavoritesUiState.Error] stays exactly for the case it always covered: nothing
         * usable is on screen yet.
         *
         * This is also the acceptance path for "unfavouriting elsewhere removes the entry from
         * this view": [repository.refreshFavorites] re-fetches `favorite=true` from the server, so
         * a title unfavourited from Detail or Library simply stops coming back the next time this
         * runs — there is no separate reconciliation step needed.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            if (refreshInFlight) return
            refreshInFlight = true
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
                    val stillShowing = mutableState.value as? FavoritesUiState.Success
                    mutableState.value = stillShowing?.copy(isStale = true) ?: FavoritesUiState.Error(failure)
                } finally {
                    refreshInFlight = false
                    // Cleared by loadMore() itself, only once it actually commits to a fetch — see
                    // the field's own comment for the stall a premature clear reintroduces.
                    if (pendingLoadMoreAfterRefresh) {
                        loadMore()
                    }
                }
            }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore`/
         * `DiscoverViewModel.loadMore` use: a `LazyColumn`'s end-reached callback fires on every
         * frame near the bottom, and without this a scroll near the bottom would queue up a fetch
         * per frame.
         *
         * **Also deferred while [refreshInFlight] (whole-branch fix round, BLOCKING 4).** This is
         * the seventh instance this phase of "a guard that exists but is not applied at the call
         * site that needs it": [refreshInFlight] was already here and already guarding [refresh],
         * and `DiscoverViewModel.loadMore` — driving the structurally identical repository —
         * already had this half. This one did not, so a resume-driven [refresh] and a scroll-driven
         * [loadMore] were free to overlap. That is the interleaving `LibraryRepositoryImpl`'s
         * `loadMoreFavorites` fix closes at the data layer; the pair matters because they are the
         * two ends of the same window, and only closing one leaves the UI reporting "not loading"
         * during a live fetch and spending a duplicate round trip.
         *
         * DEFERRED, not dropped: [pendingLoadMoreAfterRefresh] is what re-issues it — see that
         * field's own comment for why `EndOfListTrigger` cannot be relied on to do so.
         *
         * Routed through [FavoritesUiState.Success.pageError], never [FavoritesUiState.Error]: the
         * rows a failed page-2 fetch left behind are still valid and still on screen.
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            if (refreshInFlight) {
                pendingLoadMoreAfterRefresh = true
                return
            }
            val current = mutableState.value as? FavoritesUiState.Success ?: return
            if (current.loadingMore) return
            pendingLoadMoreAfterRefresh = false
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
