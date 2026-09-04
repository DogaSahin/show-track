package com.anarky.showtrack.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.paging.CursorPaginator
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.FeedEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The group activity feed (task 9c.4). Unlike [com.anarky.showtrack.feature.groups.GroupDetailViewModel],
 * this class has no [androidx.lifecycle.SavedStateHandle] route to read a group id from: `FeedRoute`
 * is a bare `data object` (`Routes.kt`), because Feed is a TOP-LEVEL TAB, not a leaf pushed with an
 * argument — [com.anarky.showtrack.feature.groups.GroupDetailRoute]'s `groupId` works only because
 * `GroupDetailScreen` is reached by tapping a specific group. So [selectGroup] is this class's own
 * substitute entry point: [FeedScreen]'s stateful wrapper calls it with whatever `activeGroupId` it
 * was handed, on every resume (Ruling 1, `progress.md`; and see [FeedScreen]'s own KDoc for why
 * that parameter is null today and stays that way until task 9c.5 wires a real one).
 *
 * No `init { }` load, matching [com.anarky.showtrack.feature.groups.GroupsViewModel] and not
 * [com.anarky.showtrack.feature.groups.GroupDetailViewModel]'s `init { refresh() }`: this class has
 * nothing to load until [selectGroup] supplies a group id, and [FeedScreen]'s own
 * `LifecycleResumeEffect` is what calls it, mirroring `GroupsScreen`'s identical mechanism for the
 * identical reason — a tab is revisited repeatedly, not pushed once.
 *
 * **Round 1 fix (BLOCKING): this is the first ViewModel in this codebase that OUTLIVES the subject
 * it pages.** [com.anarky.showtrack.feature.groups.GroupDetailViewModel] cannot hit this — its
 * `groupId` comes from `SavedStateHandle` and never changes for the instance's life, so a stale
 * `reload`/`loadMore` continuation always belongs to the only group the instance ever knew. This
 * class's `groupId`/[paginator] genuinely change under it, via [selectGroup]. [generation] is what
 * makes every continuation check "is my subject still current" before it writes [mutableState]: a
 * `loadMore()` for group A that is still in flight when [selectGroup] switches to group B, and
 * lands only afterward, must not overwrite B's already-rendered rows with A's late page — see
 * [reload] and [loadMore]'s own KDoc for the exact check. Rebuilding [paginator] per group (the
 * ORIGINAL defence, still true) only stops a CURSOR crossing groups; it says nothing about a
 * CONTINUATION crossing groups, which is the actual shape of this bug.
 */
@HiltViewModel
class FeedViewModel
    @Inject
    constructor(
        private val groupRepository: GroupRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
        val state: StateFlow<FeedUiState> = mutableState.asStateFlow()

        // Which group [paginator] currently fetches for, or null before the first [selectGroup]
        // call. Read-compared in [selectGroup] to tell an ordinary resume (the SAME group, where a
        // refresh must keep whatever is already on screen) apart from a genuine switch (a
        // DIFFERENT group, where the old group's rows must not linger under the new group's tab —
        // `LibraryViewModel.selectStatus`'s identical reasoning for why a filter change forces a
        // blank rather than reusing `refresh()`'s keep-on-screen path).
        private var groupId: String? = null

        // `CursorPaginator`, not hand-rolled cursor/exhaustion fields — Global Constraints names
        // the exact failure task 9c.3 round 0 shipped by hand-rolling this: a duplicate first-page
        // fetch that crashed the LazyColumn with a duplicate LazyColumn key. Held on THIS
        // ViewModel, not a `@Singleton` repository — `GroupDetailViewModel.watchlistPaginator`'s
        // identical placement and identical reasoning: the feed belongs to ONE group, shown on ONE
        // screen, and this ViewModel is already scoped to exactly that (`hiltViewModel()`, scoped
        // to FeedRoute's own NavBackStackEntry). Rebuilt from scratch in [selectGroup] whenever the
        // group actually changes, so a paginator never fetches page 2 of one group with a cursor
        // that belongs to another.
        private var paginator: CursorPaginator<FeedEntry>? = null

        // A monotonic counter, bumped every time [selectGroup] switches to a DIFFERENT group —
        // round 1 fix. [reload]/[loadMore] each capture the generation in effect when they LAUNCH
        // and compare it against the CURRENT value of this field before every write to
        // [mutableState]; a mismatch means the subject changed while the fetch was in flight, so
        // the result is silently dropped rather than clobbering whatever the newer generation has
        // already rendered. This is the actual fix for the group-switch race (this class's own
        // KDoc) — [paginator] being rebuilt per group stops a stale CURSOR, but only this counter
        // stops a stale CONTINUATION.
        private var generation = 0

        // Which [generation] currently has a [refresh]-driven [reload] in flight, or null — round 1
        // fix (small item 1). Not a plain `Boolean`: a `Boolean` cannot tell "the OLD generation's
        // now-irrelevant reload is still unwinding its own `finally`" apart from "the CURRENT
        // generation's reload is genuinely still running", and a shared `Boolean` cleared by
        // EITHER one would let a third caller start a redundant fetch behind the current
        // generation's own in-flight one. Storing the GENERATION alongside "in flight" is what lets
        // [reload]'s own `finally` clear this ONLY when it still refers to the attempt that set it
        // — see that function's own KDoc. Guards double-tapping Retry, or a resume racing a retry
        // still in flight, the same "wasted round trip, not corruption" gap `FavoritesViewModel.refresh`
        // has today (that class is not this task's to fix) — the mutex inside [CursorPaginator]
        // already keeps `paginator`'s own state consistent either way, so this guard's only job is
        // to skip the SECOND redundant network round trip, not to prevent corruption that was never
        // possible in the first place.
        private var loadingGeneration: Int? = null

        /**
         * Called by [FeedScreen]'s stateful wrapper with the currently active group, on every
         * resume — never on a bare recomposition, since [FeedScreen]'s own `LifecycleResumeEffect`
         * is what invokes it. A no-op for `groupId == null`: there is nothing to select yet
         * (`FeedScreen`'s own KDoc), and the caller already renders the no-groups empty state
         * straight off its own parameter without ever reaching this class.
         *
         * Two cases, both ending in exactly ONE [reload] launch — never two, which an earlier draft
         * of this function risked by calling [refresh] as a SEPARATE step after switching (see
         * this task's own report for the concurrent-double-fetch scenario that would have
         * produced):
         *
         * - **The group changed** (including the very first call, where [groupId] is still null):
         *   [generation] is bumped FIRST — round 1 fix, and load-bearing ordering: [refresh] below
         *   reads [generation] to decide both its [loadingGeneration] guard and the value it
         *   captures for [reload], so the bump must land before that call, not after. A fresh
         *   [paginator] is built for the new group, and [mutableState] is blanked to
         *   [FeedUiState.Loading] unconditionally — the OLD group's rows must never linger on
         *   screen under the NEW group's tab, even if they were a [FeedUiState.Success].
         *   `LibraryViewModel.selectStatus`'s identical "a filter change forces a blank, a retry
         *   does not" split, applied here to a group switch instead of a filter.
         * - **The group is unchanged** (the ordinary resume-on-the-same-tab case): falls straight
         *   through to [refresh], which applies the settled refresh shape — blank only if nothing
         *   worth keeping is already on screen, otherwise reload in place and mark stale on
         *   failure.
         */
        fun selectGroup(groupId: String) {
            if (groupId != this.groupId) {
                this.groupId = groupId
                generation++
                paginator =
                    CursorPaginator { cursor ->
                        val page = groupRepository.feed(groupId, cursor)
                        Page(items = page.items, nextCursor = page.nextCursor)
                    }
                mutableState.value = FeedUiState.Loading
            }
            refresh()
        }

        /**
         * The retry button's own function (`ErrorState`/`StaleDataBanner`, both wired here
         * undifferentiated — `LibraryViewModel.refresh`'s identical shape), and [selectGroup]'s own
         * tail call for both the changed- and unchanged-group cases. A no-op when no group has ever
         * been selected ([paginator] is null) — there is nothing to retry yet, and no
         * [FeedUiState.Error] this screen could have shown to make the retry button reachable in
         * the first place.
         *
         * **Round 1 fix (small item 1): guarded on [loadingGeneration] before launching anything.**
         * `loadingGeneration == generation` means a [reload] for the CURRENT generation is already
         * running — a double-tap on Retry, or a resume arriving mid-retry, is dropped rather than
         * launching a second, redundant `restart()`. Not a bug fix for corruption (`CursorPaginator`'s
         * own `Mutex` already serialises the two `restart()` calls either way — this class's own
         * KDoc), only for the wasted round trip. **Never mistaken for a stuck flag across a group
         * switch**: [selectGroup] bumps [generation] on every switch, which makes
         * `loadingGeneration == generation` false again immediately (the stored [loadingGeneration]
         * still names the OLD generation), so a switch made while a refresh is in flight always
         * gets its own, fresh [reload] launched — see [reload]'s own `finally` for why the OLD
         * attempt's completion cannot clear a NEWER generation's guard out from under it.
         */
        fun refresh() {
            val paginator = paginator ?: return
            if (loadingGeneration == generation) return
            loadingGeneration = generation
            if (mutableState.value !is FeedUiState.Success) {
                mutableState.value = FeedUiState.Loading
            }
            val myGeneration = generation
            viewModelScope.launch {
                try {
                    reload(paginator, myGeneration)
                } finally {
                    // Only clear the guard if it STILL names the attempt that set it (round 1
                    // fix): without this check, an OLD generation's reload finishing its `finally`
                    // AFTER a switch has already started a NEWER generation's own reload would
                    // clear [loadingGeneration] out from under the newer one, letting a THIRD
                    // caller slip a redundant fetch in behind it.
                    if (loadingGeneration == myGeneration) loadingGeneration = null
                }
            }
        }

        /**
         * The actual fetch [selectGroup]/[refresh] share, via [CursorPaginator.restart] — takes
         * [paginator]'s own `Mutex` for the whole round trip, `GroupDetailViewModel.reloadWatchlist`'s
         * identical mechanism. `.copy()` off whatever [FeedUiState.Success] is already on screen
         * (round 2's lesson from this same phase — a field-by-field rebuild silently drops every
         * field added later), falling back to a fresh [FeedUiState.Success] only on the very first
         * successful load. On failure, an already-[FeedUiState.Success] screen is marked
         * [FeedUiState.Success.isStale] instead of replaced by [FeedUiState.Error] — only a load
         * with nothing already on screen produces that.
         *
         * **Round 1 fix (BLOCKING): [myGeneration] is checked, on BOTH the success and the failure
         * path, immediately before every write to [mutableState].** [paginator] itself is captured
         * as a PARAMETER (unchanged from before this fix) so the fetch always runs against the
         * cursor it was launched with; [myGeneration] is the NEW guard, and it answers a different
         * question — not "which cursor", but "is this result still wanted at all". A group switch
         * that lands while this suspend function is inside [CursorPaginator.restart] bumps
         * [generation] out from under it; by the time `restart()` returns (or throws), [generation]
         * no longer equals [myGeneration], and this function drops its own result rather than
         * writing group A's rows (or group A's failure) over whatever group B has already rendered.
         * This is the direct fix for the reviewer-reproduced repro: `[(group-A, null), (group-A,
         * cursor-2), (group-B, null)]` landing as `Success(entries=[a1, a2])` instead of the correct
         * `Success(entries=[b1])`.
         */
        private suspend fun reload(
            paginator: CursorPaginator<FeedEntry>,
            myGeneration: Int,
        ) {
            try {
                val entries = paginator.restart()
                if (generation != myGeneration) return
                val previous = mutableState.value as? FeedUiState.Success
                val base = previous ?: FeedUiState.Success(entries = entries)
                mutableState.value = base.copy(entries = entries, isStale = false, pageError = null)
            } catch (failure: GroupOperationException) {
                if (generation != myGeneration) return
                val stillShowing = mutableState.value as? FeedUiState.Success
                mutableState.value = stillShowing?.copy(isStale = true) ?: FeedUiState.Error(failure.failure)
            }
        }

        /**
         * The re-entrancy-guarded shape `LibraryViewModel.loadMore`/`GroupDetailViewModel.loadMoreWatchlist`
         * establish (Global Constraints). A page-fetch failure here is [FeedUiState.Success.pageError]
         * — a footer, never a promotion to [FeedUiState.Error] — and [FeedUiState.Success.entries]
         * is left untouched either way, [CursorPaginator.loadMore]'s own "leaves items untouched on
         * a throw" guarantee. Deliberately never touches [FeedUiState.Success.isStale] — that is
         * [refresh]'s own channel; the two failures need two channels (decision C-S; see
         * [FeedUiState.Success]'s own KDoc).
         *
         * **Round 1 fix (BLOCKING): [myGeneration] captured at launch and checked before both
         * writes below**, [reload]'s identical reasoning applied to a page fetch instead of a
         * restart: a `loadMore()` for group A that is still awaiting its response when [selectGroup]
         * switches to group B must not, on landing, splice group A's page 2 onto — or overwrite —
         * group B's freshly-rendered [FeedUiState.Success]. [current] (captured before launch, for
         * the [FeedUiState.Success.copy] calls below) still belongs to whichever generation was
         * active at call time; the generation check is what stops it from being written back after
         * the subject moved on, not a change to what [current] itself holds.
         */
        fun loadMore() {
            val paginator = paginator ?: return
            val current = mutableState.value as? FeedUiState.Success ?: return
            if (current.loadingMore) return
            if (!paginator.hasMore.value) return
            val myGeneration = generation
            // pageError cleared HERE, before the fetch is even launched (decision C-S: "clear the
            // error before launching a retry, not only on success") — GroupDetailViewModel's
            // loadMoreWatchlist fix round 2 lesson, applied from the start here rather than
            // rediscovered.
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    paginator.loadMore()
                    if (generation != myGeneration) return@launch
                    val latest = mutableState.value as? FeedUiState.Success ?: return@launch
                    mutableState.value =
                        latest.copy(entries = paginator.items.value, loadingMore = false, pageError = null)
                } catch (failure: GroupOperationException) {
                    if (generation != myGeneration) return@launch
                    val latest = mutableState.value as? FeedUiState.Success ?: return@launch
                    mutableState.value = latest.copy(loadingMore = false, pageError = failure.failure)
                }
            }
        }
    }
