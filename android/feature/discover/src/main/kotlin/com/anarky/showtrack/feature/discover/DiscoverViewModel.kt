package com.anarky.showtrack.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.model.Recommendation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The recommendations feed, and its one-tap add (decision D-I).
 *
 * The constructor names two INTERFACES from `:core:data` and nothing else — architecture rule 2,
 * structural rather than a review item, the same shape `SearchViewModel`/`LibraryViewModel` use.
 *
 * **`state` is a plain [MutableStateFlow], not `combine(...).stateIn(WhileSubscribed(5_000))` the
 * way `LibraryViewModel.state` is** (decision C-U). `WhileSubscribed` exists on the library screen
 * to stop a continuously-updating, Room-backed [kotlinx.coroutines.flow.Flow] from being
 * re-collected (and its query re-run) for a screen nobody is watching. Nothing here is like that:
 * [RecommendationRepository] has no Room-backed upstream at all — see its own KDoc for why a
 * recommendation is never cached — so there is no independent background writer to gate a
 * subscription against, and `[RecommendationRepository.refresh]`/[loadMore]/[add] are one-shot
 * suspend calls THIS ViewModel drives itself, exactly `SearchViewModel`'s shape rather than
 * `LibraryViewModel`'s.
 *
 * **Three operations, three failure channels** — see [DiscoverUiState]'s KDoc for the full
 * reasoning (carried forward from task 9a.8's shipped bug, which is exactly this screen's shape).
 *
 * **Decision, task 9c.8 (E-M): Discover refetches on resume, matching [FavoritesViewModel]/
 * `ProfileViewModel`.** Before this task, this screen was the one data screen with neither a
 * resume refresh nor an `isStale` — it loaded once, in `init`, and the tab's `ViewModelStore`
 * survives a tab switch (`ShowTrackNavHost` uses `saveState`/`restoreState`), so adding a title
 * from Detail or Search and returning to Discover left it still listed as a recommendation, even
 * though the backend's contract is that anything already in the library is excluded from this
 * feed. Decision D-I reasoned carefully about `add`'s own failure path — restoring the row at its
 * original index, never refetching, because a refetch mid-read re-ranks the whole feed under a
 * user still looking at it — but said nothing about the absence of a trigger to refetch at all on
 * the ordinary "left and came back" path.
 *
 * **Round 1 correction (review finding B4): this paragraph used to claim the refetch's cost was
 * "a one-frame reflow", on the grounds that "nobody is mid-read of a screen they just resumed."
 * That is false on this screen's single most common resume path, Detail -> Back: the user tapped
 * a row specifically because they were reading it, and returns expecting the SAME scroll position
 * — they are exactly as mid-read as they were before tapping.** Combined with the page-1
 * truncation named below (this decision's own pre-existing, known gap — not new here), the actual,
 * accepted cost on that path is real and worth stating plainly: page down to 60 rows, tap a title,
 * come back, and the list COLLAPSES to 20 rows with scroll clamped toward the end — even though
 * nothing changed and no add happened. That is a visible regression on the MAJORITY of resumes,
 * not a harmless reflow.
 *
 * Weighed against that corrected cost, the asymmetry that actually settles this is narrower than
 * originally claimed: a recommendation for a title the user already tracks is not a merely-stale
 * row the way an unrated library entry's stats are — it is actively WRONG, an assertion the
 * backend's own contract has already retracted, and is exactly what a user returning from
 * Search/Detail-with-an-add is checking for. A wrong recommendation on the ADD path is worse than
 * a truncated-but-still-correct list on the NO-ADD path (the majority case) — this task accepts
 * the truncation as the explicit, named cost of closing the wrong-recommendation gap, rather than
 * treating the truncation as free. `DiscoverScreen`'s `LifecycleResumeEffect` is the trigger, and
 * [refresh] itself is what changed to carry it safely: [DiscoverUiState.Success.isStale] (the
 * "settled refresh shape" the Global Constraints name) means a resume's fetch no longer blanks a
 * populated feed to a spinner, and a resume's FAILURE marks the feed stale instead of replacing it
 * — see [refresh]'s own KDoc.
 *
 * **What this decision deliberately does NOT do** (holding two follow-ups, not building them here):
 * it refetches on EVERY resume, with no staleness TTL gating a Detail -> Back round trip (where a
 * refetch buys nothing — no add happened, but the truncation above still fires) against a
 * Search -> add -> Back one (where the refetch buys everything) — a TTL constant is a product
 * judgement outside this task's scope, so a future caller of [refresh] from a resume effect is the
 * intended place to add one, not this function; that TTL is also the real fix for the truncation
 * cost accepted above, not a larger change here. This screen also still truncates to page 1 on
 * every refresh (`RecommendationRepositoryImpl.refresh`'s own shape) rather than reloading every
 * page the user had scrolled into — `CursorPaginator` has no `refreshLoadedPages` equivalent for
 * this repository, and adding one is `:core:data` API surface this task does not touch. Both are
 * real, known gaps, not oversights.
 *
 * **No `init { refresh() }` any more (round 1 of this task).** An `init` alongside the new resume
 * effect would be the identical redundant-fetch bug `FavoritesViewModel`'s own KDoc documents for
 * the identical reason: `LifecycleResumeEffect` already fires on the very first composition, not
 * only a later resume, so an `init` here would not cover a gap the resume effect misses — it would
 * be a second, wasted `GET` on every cold start.
 *
 * **The dropped-call rule (task 9c.8 round 4), governing all three operations.** [refresh],
 * [loadMore] and [add] can now overlap, and every early `return` any of them makes is a call
 * somebody asked for and did not get. The rule this class applies, and the one to carry forward:
 *
 * > A dropped call is acceptable only if something will re-issue it, or the user can see it was
 * > dropped. Otherwise defer it, don't discard it.
 *
 * This replaces round 3's "never drop a user's tap; accept dropping a background refresh", which
 * had the wrong discriminator. What made round 2's dropped tap bad was not that it came from a
 * finger — it was that the drop was UNOBSERVABLE and UNRECOVERED: no spinner, no error, and
 * nothing to re-issue it. [loadMore]'s own drop is fine for the first clause
 * ([pendingLoadMoreAfterRefresh] re-fires it), not because a scroll is "unauthored". And the
 * old wording actively endorsed the guard round 4 had to remove: [refresh] used to drop itself
 * while [addInFlight] was set, on the stated grounds that it "has no user-visible affordance of
 * its own" — false, since `DiscoverScreen` wires `onRetry = viewModel::refresh` to both
 * `ErrorState` and `StaleDataBanner`, so that drop silently swallowed a Retry tap on the stale
 * banner for the duration of a `POST`.
 *
 * Each surviving drop names the clause that justifies it in its own KDoc. One drop is justified by
 * neither and is kept anyway on a stated correctness precondition — see [add].
 */
@HiltViewModel
class DiscoverViewModel
    @Inject
    constructor(
        private val recommendationRepository: RecommendationRepository,
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
        val state: StateFlow<DiscoverUiState> = mutableState.asStateFlow()

        // A ViewModel-wide guard, independent of [state]'s shape — [addInFlight] below's identical
        // reasoning, applied to [refresh] instead of [add] (task 9c.8, E-M): once a resume effect
        // can call [refresh] over an already-[DiscoverUiState.Success] screen, a manual retry from
        // `StaleDataBanner`/[DiscoverUiState.Error] can land WHILE that resume fetch is still in
        // flight — the exact "a manual retry races a resume-triggered fetch" shape
        // `FavoritesViewModel.refresh`/`ProfileViewModel.refreshStats` were fixed for in this same
        // task, reproduced here the moment this screen gained a second caller of [refresh]. Without
        // it, last-write-wins between the two calls can mark freshly-loaded data stale, or clobber
        // it with an older response landing second.
        //
        // **A dropped re-entrant call, not a coalesced one** (review finding M2): a SECOND call
        // landing while this flag is `true` is discarded outright, not queued to run again once
        // the first finishes. `FeedViewModel.loadingGeneration` is the shape that WOULD coalesce —
        // capturing a generation and re-checking it, rather than a bare boolean — and was not
        // adopted here because [refresh] has no "subject" that can go stale out from under it the
        // way a group switch does; the accepted cost is narrower: a slow in-flight [refresh]
        // racing a state-changing event elsewhere (an add landing between this resume's request
        // and its response, say) can render the OLDER response with no automatic follow-up retry —
        // the next resume is what corrects it, not this call.
        //
        // **Against the dropped-call rule (round 4, see this class's own KDoc), this drop is
        // justified by BOTH clauses.** Clause 1 ("something will re-issue it"): the dropped call is
        // not lost work, it is a duplicate — an identical fetch is ALREADY in flight and its
        // response is published to this same screen, so a Retry tapped mid-flight gets the thing it
        // asked for, just from the earlier call. Clause 2 ("the user can see it was dropped"): if
        // that in-flight fetch fails, [DiscoverUiState.Success.isStale] stays set, the
        // `StaleDataBanner` stays on screen, and its Retry is live for another tap — the user is
        // never left looking at a screen that silently ignored them.
        //
        // **Cross-checked by NOBODY else.** [add] does not check this flag (round 3, review
        // finding BLOCKING: dropping a direct tap for the whole of every resume refresh was worse
        // than the crash it prevented), and [refresh] no longer checks [addInFlight] either
        // (round 4 — that drop swallowed the `StaleDataBanner` Retry; see [refresh]'s own KDoc for
        // the mechanism that replaced it). [loadMore] still checks it, and defers rather than
        // discards — see [pendingLoadMoreAfterRefresh].
        private var refreshInFlight = false

        // A single ViewModel-wide guard, not one per row — same shape and same reasoning as
        // `SearchViewModel.addInFlight`: a field the guard can always read regardless of `state`'s
        // shape, rather than a value scoped inside `DiscoverUiState.Success` that a concurrent
        // state replacement could reset out from under it.
        //
        // **This field is about RE-ENTRANCY ONLY — it is no longer what keeps an optimistically
        // removed row hidden (round 4, review finding BLOCKING 1).** It is cleared by [add]'s own
        // `finally` the instant the `POST` returns, which is exactly the window the round-3 fix got
        // wrong: a `POST /v1/library` that resolves BEFORE a concurrent [refresh]'s `GET` lands
        // left nothing for that refresh to re-remove, and the row the user had just successfully
        // added was republished — the very defect this task exists to fix, and the likely ordering
        // rather than an edge case (a small write finishes ahead of a page-1 read that runs scoring
        // and aggregation). [optimisticallyRemovedIds] is what outlives the `POST`; see its KDoc.
        private var addInFlight: String? = null

        // The ids this screen has optimistically taken off [DiscoverUiState.Success.items] and has
        // NOT been told to put back — the memory that survives the `POST` (round 4, review finding
        // BLOCKING 1). An id enters here in [add], next to the [RecommendationRepository.remove]
        // that hides the row, and leaves on exactly two events:
        //
        //  1. the add FAILED and the row is being restored ([add]'s `catch`) — the row is the
        //     user's to see again, and its retry affordance renders from it;
        //  2. a successful [refresh]'s fresh page no longer names it — the server has now applied
        //     the add itself, so there is nothing left to suppress. This prune is what keeps the
        //     set from growing for the process's lifetime, and what stops a title the user later
        //     removes from their library from being hidden here forever: once the backend is
        //     willing to recommend it again, this screen is willing to show it again.
        //
        // While an id is in here, EVERY server-sourced republish re-applies its removal — both
        // [refresh]'s wholesale page-1 replacement and [loadMore]'s append. Doing it per-publish
        // rather than per-in-flight-POST is the point: the window in which the backend still names
        // a row the user has added is bounded by the backend's own write, not by the client's
        // knowledge of it.
        //
        // Kept HERE rather than pushed into `RecommendationRepositoryImpl` (the alternative the
        // review offered): that would make the invariant unmissable for any future consumer of the
        // repository, which is genuinely better in the abstract, but the repository has no way to
        // learn that an add FAILED — the event that ends a suppression — so it would have to guess
        // it from page contents alone, and it would give a process-lifetime `@Singleton` hidden
        // state on behalf of one screen's optimistic UI. This ViewModel already owns the add's
        // lifecycle; this field is just that knowledge outliving one `POST`.
        private val optimisticallyRemovedIds = mutableSetOf<String>()

        // Set by [loadMore] when it drops itself because [refreshInFlight] (task 9c.8 round 2,
        // review finding "B3 guard can stall paging"). `EndOfListTrigger` only emits
        // [DiscoverScreen]'s `onTriggered` on the false -> true edge of its own `shouldTrigger`
        // (that composable's own KDoc); a `loadMore()` dropped here changes neither `itemCount`
        // nor scroll position, so `shouldTrigger` never toggles and the trigger never re-fires on
        // its own — without this flag, Discover would stop paging silently until the user
        // scrolled up past the threshold and back down. [refresh]'s own `finally` checks this once
        // [refreshInFlight] is clear and re-issues the dropped [loadMore] itself, restoring paging
        // without any Compose-layer involvement.
        //
        // **Cleared only when the re-issued [loadMore] actually commits to a fetch, not merely
        // called (task 9c.8 round 3, review finding).** An earlier version of this mechanism
        // cleared the flag unconditionally in [refresh]'s own `finally`, BEFORE calling [loadMore]
        // — which drops the SAME signal a second time if the re-issued call itself bails out (e.g.
        // [DiscoverUiState.Success.loadingMore] is still `true` from a DIFFERENT `loadMore()` that
        // is genuinely still in flight): the flag was already gone by then, so nothing remembers to
        // retry once that other fetch finishes, and the exact stall this mechanism exists to fix
        // reappears. [loadMore] itself now clears this flag, and only at the point it actually
        // writes `loadingMore = true` and launches — never inside its own early-return guards.
        private var pendingLoadMoreAfterRefresh = false

        /**
         * The initial load (via `DiscoverScreen`'s `LifecycleResumeEffect` — see this class's own
         * KDoc for why there is no `init` any more), a retry from [DiscoverUiState.Error], and a
         * resume over [DiscoverUiState.Success] (task 9c.8, E-M) — the settled refresh shape
         * `FavoritesViewModel.refresh`/`ProfileViewModel.refreshStats` establish, applied here.
         *
         * [DiscoverUiState.Loading] is written wholesale ONLY when [state] is not already
         * [DiscoverUiState.Success] — writing it unconditionally is exactly right for the first
         * load and for a retry from [DiscoverUiState.Error] (decision C-S: clear the error before
         * launching a retry, not only on success), but would blank an already-populated feed to a
         * full-screen spinner on every resume otherwise, resetting scroll position for a round trip
         * the user never asked for.
         *
         * **On success, `copy()`s the existing [DiscoverUiState.Success] rather than rebuilding one
         * field-by-field (review finding B2, round 1).** Before this fix, a successful resume wrote
         * `DiscoverUiState.Success(items = ...)` outright, silently defaulting
         * [DiscoverUiState.Success.loadingMore]/[DiscoverUiState.Success.pageError]/
         * [DiscoverUiState.Success.addError] back to their defaults — harmless before this task,
         * when [refresh] was reachable only from [DiscoverUiState.Error] (nothing to lose there),
         * but not once a resume can land [refresh] OVER a populated screen: a concurrent [loadMore]
         * mid-flight would have its own [DiscoverUiState.Success.loadingMore] flag silently flipped
         * back to `false` here while the page fetch was still genuinely running, freeing
         * `EndOfListTrigger` to fire a SECOND, concurrent `loadMore()` — `FeedViewModel.reload`'s
         * identical lesson (see its own KDoc), applied here. [pageError] is explicitly cleared
         * (not merely carried forward), matching `GroupDetailViewModel.reloadWatchlist`'s own
         * precedent: a successful refresh is a strictly newer, authoritative read, so a stale
         * page-fetch error must not survive it.
         *
         * On failure: if [state] is still [DiscoverUiState.Success] when the failure lands, this
         * marks [DiscoverUiState.Success.isStale] instead of replacing it with
         * [DiscoverUiState.Error] wholesale — a failed BACKGROUND resume must not destroy a feed the
         * user is already reading. [DiscoverUiState.Error] stays reachable for the case it always
         * covered: nothing usable is on screen yet.
         *
         * **No longer drops while an add is in flight (task 9c.8 round 4, review finding
         * BLOCKING 2 — round 2 added that guard, round 4 removes it).** `if (addInFlight != null)
         * return` was defended on the grounds that this function "has no user-visible affordance of
         * its own the way [add] has a tap and a retry". That was simply false: `DiscoverScreen`
         * wires `onRetry = viewModel::refresh` to BOTH `ErrorState` and `StaleDataBanner`. The
         * concrete defect it caused: the feed is stale after a failed resume, the user taps Add on
         * a row (so a `POST` is in flight), then taps Retry on the stale banner — and this function
         * returned silently, banner still up, no spinner, nothing re-issuing it. That is exactly
         * the swallowed-tap defect round 3 removed from [add], in the mirror direction, and the
         * dropped-call rule in this class's KDoc refuses it: nothing re-issues the retry and the
         * user cannot see it was dropped. (Its reach was narrow — `ErrorState`'s retry is
         * unreachable this way, since no rows means no Add button means [addInFlight] can never be
         * set — but the reasoning behind it was load-bearing for the whole round-3 asymmetry.)
         *
         * **Re-applies [optimisticallyRemovedIds] AFTER a successful fetch, and prunes it against
         * the page it just read (round 4, review finding BLOCKING 1).** Round 3 re-applied
         * `addInFlight?.let { recommendationRepository.remove(it) }` here instead, which closed only
         * the sub-window in which the `POST` was still running: a `POST` that resolved BEFORE this
         * call's `GET` landed had already cleared [addInFlight] in [add]'s `finally`, so nothing was
         * re-removed and the row the user had SUCCESSFULLY added came back — the likely ordering,
         * not an edge case, and precisely the wrong-recommendation defect this task exists to fix.
         * (Tapping Add on the resurrected row then sends a duplicate `POST` and can surface an
         * [DiscoverUiState.Success.addError] on a title that was in fact added.) The set is what
         * outlives the `POST`; the prune, `retainAll` against the ids the fresh page still names, is
         * what ends a suppression once the backend has caught up — see that field's own KDoc.
         *
         * [add]'s own `restore()` on failure still uses its captured `originalIndex` against
         * whatever list is current by then; `RecommendationRepository.restore`'s existing clamp
         * (`coerceIn(0, size)`) is what its own KDoc already argues is defensive for exactly this
         * kind of reshuffle, so no change is needed there.
         *
         * **Re-issues a [loadMore] dropped by [refreshInFlight] once this call actually finishes**
         * (task 9c.8 round 2, review finding "B3 guard can stall paging") — see
         * [pendingLoadMoreAfterRefresh]'s own KDoc for why `EndOfListTrigger` cannot be trusted to
         * do this on its own.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            if (refreshInFlight) return
            refreshInFlight = true
            if (mutableState.value !is DiscoverUiState.Success) {
                mutableState.value = DiscoverUiState.Loading
            }
            viewModelScope.launch {
                try {
                    recommendationRepository.refresh()
                    // Round 4, BLOCKING 1: prune first — an id the fresh page no longer names has
                    // been applied by the backend, so the suppression is over — then re-hide
                    // whatever it still names. See [optimisticallyRemovedIds]'s own KDoc.
                    val freshIds = recommendationRepository.feed.value.mapTo(mutableSetOf()) { it.media.id }
                    optimisticallyRemovedIds.retainAll(freshIds)
                    optimisticallyRemovedIds.forEach { recommendationRepository.remove(it) }
                    val previous = mutableState.value as? DiscoverUiState.Success
                    val base = previous ?: DiscoverUiState.Success(items = recommendationRepository.feed.value)
                    mutableState.value =
                        base.copy(
                            items = recommendationRepository.feed.value,
                            isStale = false,
                            pageError = null,
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val stillShowing = mutableState.value as? DiscoverUiState.Success
                    mutableState.value = stillShowing?.copy(isStale = true) ?: DiscoverUiState.Error(failure)
                } finally {
                    refreshInFlight = false
                    // pendingLoadMoreAfterRefresh is cleared by loadMore() itself, only once it
                    // actually commits to a fetch — never here, unconditionally (round 3 fix; see
                    // that field's own KDoc for the stall a premature clear reintroduces).
                    if (pendingLoadMoreAfterRefresh) {
                        loadMore()
                    }
                }
            }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore`/
         * `SearchViewModel.loadMore` use: a `LazyColumn`'s end-reached callback fires on every
         * frame near the bottom, and without this a scroll near the bottom would queue up a fetch
         * per frame.
         *
         * **Also dropped up front while [refreshInFlight] (review finding B3, round 1;
         * KDoc corrected round 2 — the original text here claimed a duplicate-`media.id` race
         * between [refresh] and [loadMore] that does not exist.** `RecommendationRepositoryImpl.loadMore`'s
         * append and `CursorPaginator.loadMore`'s `mutex.withLock { ... }` are inline:
         * `withLock` does not suspend on an uncontended lock, and `unlock()` does not suspend
         * either, so there is NO suspension point between `paginator.loadMore()` returning and the
         * append that follows it — nothing else, [refresh] included, can run in that window.
         * Verified empirically: all three interleavings driven on both `StandardTestDispatcher` and
         * `UnconfinedTestDispatcher` produced no duplicate in any of them.
         *
         * What this guard actually buys, corrected: [refresh] truncates [DiscoverUiState.Success.items]
         * back to page 1 unconditionally (`RecommendationRepositoryImpl.refresh`'s own shape — see
         * this class's own KDoc), which discards anything a concurrent [loadMore] would have
         * appended. Without this guard, a [loadMore] landing while a [refresh] is in flight is
         * simply WASTED work — a real network round trip whose result [refresh] is about to
         * overwrite the instant it lands. This guard is cheap containment for that waste, not a
         * correctness fix — [add]'s own KDoc has the actual duplicate-`media.id` crash this screen
         * has, a DIFFERENT pair ([add] and [refresh], not [loadMore] and [refresh]).
         *
         * The reverse ordering does not need closing: a [loadMore] already in flight when a resume
         * calls [refresh] produces the SAME page-1 truncation [refresh] always produces on its own
         * — never a duplicate — so there is nothing here for [refresh] to defer to.
         *
         * A call dropped here sets [pendingLoadMoreAfterRefresh] so [refresh] can re-issue it once
         * it actually finishes — see that field's own KDoc for why `EndOfListTrigger` on its own
         * would otherwise leave Discover silently stuck. Against the dropped-call rule (this
         * class's KDoc) that satisfies clause 1 outright: this is a DEFERRAL, not a discard. The
         * other early return here — [DiscoverUiState.Success.loadingMore] already `true` — is
         * likewise a duplicate of a fetch already running, whose spinner is on screen (clause 2).
         *
         * **Re-applies [optimisticallyRemovedIds] to the appended page (round 4).** Server-sourced
         * republishing resurrecting an optimistically hidden row is not unique to [refresh]'s
         * wholesale replacement: `RecommendationRepositoryImpl.loadMore` appends whatever the next
         * page named, and if the backend has not yet applied the add, that page can name a row this
         * screen has already taken off the list. Same suppression, same set, applied here too —
         * this function is the second of the two publish paths that read from the server, and
         * fixing only the reported one is how this task has produced an adjacent regression each
         * round.
         *
         * Routed through [DiscoverUiState.Success.pageError], never [DiscoverUiState.Error]: the
         * rows a failed page-2 fetch left behind are still valid and still on screen.
         *
         * **`finally` added, task 9c.8 round 2 (review finding).** [DiscoverViewModel.refresh]'s own
         * `copy()` fix (round 1, B2) removed an ACCIDENTAL recovery path this function used to lean
         * on without anyone noticing: the OLD field-by-field `Success(items = ...)` rebuild silently
         * reset a stuck [DiscoverUiState.Success.loadingMore] back to `false` on the very next
         * successful [refresh] — so a [CancellationException] escaping [recommendationRepository]'s
         * fetch here (the one type the `catch` below deliberately never absorbs) used to self-heal
         * on the next resume. `copy()` now faithfully PRESERVES `loadingMore`, which means it also
         * faithfully preserves a stuck one, forever, once nothing resets it. The `finally` below is
         * that reset — `GroupDetailViewModel.loadMoreWatchlist`'s identical shape: no
         * `return@launch` (would swallow an in-flight exception instead of letting it propagate),
         * and only writes when [DiscoverUiState.Success.loadingMore] is still `true` (a no-op on
         * the two paths above that already cleared it).
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            if (refreshInFlight) {
                pendingLoadMoreAfterRefresh = true
                return
            }
            val current = mutableState.value as? DiscoverUiState.Success ?: return
            if (current.loadingMore) return
            // Cleared HERE, only once this call has passed every early-return guard above and is
            // genuinely about to fetch — round 3 fix, see this field's own KDoc for why clearing
            // it any earlier (e.g. unconditionally in refresh()'s finally) can silently drop the
            // signal a second time.
            pendingLoadMoreAfterRefresh = false
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    recommendationRepository.loadMore()
                    // Round 4: an appended page can still name a row this screen optimistically
                    // hid — see this function's own KDoc. NOT pruned here: a row's absence from
                    // page N proves nothing about the backend having applied the add, unlike
                    // [refresh]'s fresh page 1, which IS the whole feed at that instant.
                    optimisticallyRemovedIds.forEach { recommendationRepository.remove(it) }
                    replaceSuccess {
                        it.copy(items = recommendationRepository.feed.value, loadingMore = false, pageError = null)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(loadingMore = false, pageError = failure) }
                } finally {
                    val stillLoading = mutableState.value as? DiscoverUiState.Success
                    if (stillLoading?.loadingMore == true) {
                        mutableState.value = stillLoading.copy(loadingMore = false)
                    }
                }
            }
        }

        /**
         * The optimistic add (decision D-I). [recommendation]'s row is removed from
         * [DiscoverUiState.Success.items] IMMEDIATELY — before the `POST /v1/library` round trip
         * even starts — via [recommendationRepository]'s own [RecommendationRepository.remove], and
         * [libraryRepository].add is called with its `(source, externalId)`.
         *
         * On failure, the row is put back **at its original index**, never merely appended back to
         * the end, via [RecommendationRepository.restore] — NOT by patching
         * [DiscoverUiState.Success.items] directly. That distinction is load-bearing, not stylistic:
         * [loadMore] re-publishes `items` wholesale from [RecommendationRepository.feed] on success,
         * so a restore applied only to this ViewModel's copy of the list would be silently discarded
         * the next time [loadMore] succeeds — the row would vanish with no user action, and
         * [DiscoverUiState.Success.addError] would be left pointing at a `mediaId` no longer in
         * `items` at all, which also strands the retry affordance the row itself renders. Routing
         * the restore through the repository keeps [RecommendationRepository.feed] the single list
         * both [loadMore] and this failure path agree on, the same way [remove]'s permanence across
         * a later [loadMore] is guaranteed on the repository side rather than here.
         *
         * [originalIndex] is captured before the removal: restoring anywhere else would visibly
         * reshuffle the list the user is still looking at over an error that has nothing to do with
         * ordering. [RecommendationRepository.restore] clamps it against the CURRENT feed size, not
         * blindly reusing it — see that function's KDoc for why the clamp is defensive rather than
         * load-bearing for the one race that can move it (a `loadMore()` completing while this add
         * is in flight).
         *
         * Never refetches the feed on either outcome — see [DiscoverUiState]'s and this class's own
         * KDoc: a refetch re-ranks the whole feed and would move rows out from under a user who is
         * still reading them, exactly what decision D-I exists to avoid.
         *
         * **Does NOT drop while [refreshInFlight] (task 9c.8 round 3, review finding BLOCKING —
         * round 2 had this function drop instead, reverted here).** Round 2's mutual guard closed
         * the duplicate-`media.id` crash — see the paragraph below for that hazard's own shape —
         * but at a cost round 2 did not weigh: `DiscoverScreen`'s `LifecycleResumeEffect` fires
         * [refresh] on every tab switch and every resume, and the settled refresh shape keeps a
         * populated screen with live Add buttons on it for the ENTIRE round trip. Dropping here
         * meant a tap landing anywhere in that window silently did nothing — no [remove], no
         * spinner (there is no per-row "adding" field to render one from), no [DiscoverUiState.Success.addError]
         * — and `DiscoverScreen.kt`'s add-failure retry text is wired to call this SAME function, so
         * a retry tapped during a refresh silently failed to retry too, leaving the stale error on
         * screen. A direct, deliberate user action dropped for the whole of every resume — far more
         * frequent than the crash it prevented, which needs a POST failure inside that same narrow
         * window. [loadMore]'s own `refreshInFlight` guard is not the right precedent here despite
         * looking identical — but NOT for the reason round 3 gave ("a scroll side effect the user
         * never authored"; see this class's KDoc for why authorship is the wrong discriminator).
         * The real difference is that [loadMore]'s drop is a deferral — [pendingLoadMoreAfterRefresh]
         * re-fires it — whereas a dropped tap here was re-issued by nothing and shown to nobody.
         *
         * **The duplicate-`media.id` crash this class's own KDoc describes is closed by neither
         * operation dropping the other any more (round 4).** Both orderings are closed by the same
         * thing: [optimisticallyRemovedIds] records the removal here, and every server-sourced
         * republish ([refresh]'s wholesale page-1 replacement and [loadMore]'s append alike)
         * re-applies it until the backend stops naming the row. `restore()` below removes the id
         * from that set FIRST, so the row it re-inserts is the only copy: a republish can never
         * have put one back, and a later one will not take this one away. Round 3 gated the same
         * re-application on [addInFlight] instead, which held only for as long as the `POST` did —
         * see [refresh]'s KDoc for the successful-add resurrection that left open.
         *
         * A ViewModel-level set was chosen over making [RecommendationRepository.restore] idempotent
         * (filtering the id before inserting) in `RecommendationRepositoryImpl` — the alternative an
         * earlier review offered. That closes only the CRASH path (a literal duplicate entry) and
         * does nothing for the success-path republish, since `restore()` is never called on that
         * path at all. It was also chosen over moving the whole notion of "removed" into
         * `RecommendationRepositoryImpl`; see [optimisticallyRemovedIds] for that trade-off, which
         * is the closer call of the two.
         *
         * **The one drop here, and the one clause of the dropped-call rule it does NOT satisfy.**
         * `if (addInFlight != null) return` covers two different taps. A second tap on the SAME row
         * is fine under clause 2: the effect that tap asks for — the row leaving the list — is
         * already on screen, so there is nothing for the user to miss. A tap on a DIFFERENT row
         * while an add is in flight is genuinely swallowed: no [remove], no spinner (there is no
         * per-row "adding" field to render one from), no
         * [DiscoverUiState.Success.addError], and nothing re-issues it. It is kept anyway, and not
         * because the rule forgives it — because the alternative violates a stated correctness
         * precondition: `RecommendationRepository.restore`'s own KDoc records that its ABSOLUTE
         * index is safe today "only because `DiscoverViewModel.addInFlight` permits exactly one
         * outstanding remove/restore pair at a time", and two concurrent adds restoring out of
         * ascending index order would land a row in the wrong place. Widening this to a set of
         * in-flight ids therefore needs `restore`'s contract fixed first, plus per-row `adding`
         * state so the second tap has something to show for itself. Both are outside this task;
         * the residual is one `POST`'s duration, and is recorded here rather than blessed.
         */
        @Suppress("TooGenericExceptionCaught")
        fun add(recommendation: Recommendation) {
            if (addInFlight != null) return
            val current = mutableState.value as? DiscoverUiState.Success ?: return
            val originalIndex = current.items.indexOfFirst { it.media.id == recommendation.media.id }
            if (originalIndex < 0) return

            addInFlight = recommendation.media.id
            optimisticallyRemovedIds += recommendation.media.id
            recommendationRepository.remove(recommendation.media.id)
            replaceSuccess { success ->
                success.copy(items = recommendationRepository.feed.value, addError = null)
            }
            viewModelScope.launch {
                try {
                    libraryRepository.add(
                        source = recommendation.media.source,
                        externalId = recommendation.media.externalId,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Before the restore, never after: a republish landing between the two would
                    // otherwise re-hide the row this branch is putting back.
                    optimisticallyRemovedIds -= recommendation.media.id
                    recommendationRepository.restore(originalIndex, recommendation)
                    replaceSuccess { success ->
                        success.copy(
                            items = recommendationRepository.feed.value,
                            addError = AddFailure(recommendation.media.id, failure),
                        )
                    }
                } finally {
                    addInFlight = null
                }
            }
        }

        private inline fun replaceSuccess(transform: (DiscoverUiState.Success) -> DiscoverUiState.Success) {
            val latest = mutableState.value as? DiscoverUiState.Success ?: return
            mutableState.value = transform(latest)
        }
    }
