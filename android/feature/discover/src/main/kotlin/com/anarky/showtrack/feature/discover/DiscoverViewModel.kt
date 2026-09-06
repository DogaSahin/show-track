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
        private var refreshInFlight = false

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
                }
            }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore`/
         * `SearchViewModel.loadMore` use: a `LazyColumn`'s end-reached callback fires on every
         * frame near the bottom, and without this a scroll near the bottom would queue up a fetch
         * per frame.
         *
         * **Also dropped up front while [refreshInFlight] (review finding B3, round 1).** Before
         * this task, [loadMore] and [refresh] were disjoint by construction — [loadMore] required
         * [DiscoverUiState.Success], and [refresh] was reachable only from [DiscoverUiState.Error]
         * — so the two could never run concurrently against [recommendationRepository]. A resume
         * can now call [refresh] at any moment, including while a page fetch is genuinely in
         * flight. `RecommendationRepositoryImpl` sequences a REPLACE ([RecommendationRepository.refresh])
         * against an APPEND ([loadMore]) through a single shared `lastFetchedPage` field that is
         * written INSIDE `CursorPaginator`'s mutex but read back OUTSIDE it — see that class's own
         * KDoc — so an overlapping [refresh] and [loadMore] can, on an unlucky interleaving, leave
         * [DiscoverUiState.Success.items] holding a duplicate `media.id`, which crashes
         * `DiscoverScreen`'s `LazyColumn` (keyed by `media.id`) outright. This guard is the cheapest
         * containment: it stops [loadMore] from ever starting while a [refresh] is already known to
         * be in flight. It does NOT close the reverse ordering — a [loadMore] already in flight when
         * a resume calls [refresh] is not deferred — accepted here rather than closed, since
         * deferring [refresh] itself would silently drop the one signal this whole task exists to
         * deliver (the resume's own refetch) for an in-flight page fetch that, on a real device, is
         * usually much shorter-lived than the gap between resumes.
         *
         * Routed through [DiscoverUiState.Success.pageError], never [DiscoverUiState.Error]: the
         * rows a failed page-2 fetch left behind are still valid and still on screen.
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            if (refreshInFlight) return
            val current = mutableState.value as? DiscoverUiState.Success ?: return
            if (current.loadingMore) return
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    recommendationRepository.loadMore()
                    replaceSuccess {
                        it.copy(items = recommendationRepository.feed.value, loadingMore = false, pageError = null)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(loadingMore = false, pageError = failure) }
                }
            }
        }

        // A single ViewModel-wide guard, not one per row — same shape and same reasoning as
        // `SearchViewModel.addInFlight`: a field the guard can always read regardless of `state`'s
        // shape, rather than a value scoped inside `DiscoverUiState.Success` that a concurrent
        // state replacement could reset out from under it.
        private var addInFlight: String? = null

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
         */
        @Suppress("TooGenericExceptionCaught")
        fun add(recommendation: Recommendation) {
            if (addInFlight != null) return
            val current = mutableState.value as? DiscoverUiState.Success ?: return
            val originalIndex = current.items.indexOfFirst { it.media.id == recommendation.media.id }
            if (originalIndex < 0) return

            addInFlight = recommendation.media.id
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
