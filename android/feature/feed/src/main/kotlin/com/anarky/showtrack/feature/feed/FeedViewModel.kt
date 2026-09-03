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
         *   a fresh [paginator] is built for it, and [mutableState] is blanked to [FeedUiState.Loading]
         *   unconditionally — the OLD group's rows must never linger on screen under the NEW
         *   group's tab, even if they were a [FeedUiState.Success]. `LibraryViewModel.selectStatus`'s
         *   identical "a filter change forces a blank, a retry does not" split, applied here to a
         *   group switch instead of a filter.
         * - **The group is unchanged** (the ordinary resume-on-the-same-tab case): falls straight
         *   through to [refresh], which applies the settled refresh shape — blank only if nothing
         *   worth keeping is already on screen, otherwise reload in place and mark stale on
         *   failure.
         */
        fun selectGroup(groupId: String) {
            if (groupId != this.groupId) {
                this.groupId = groupId
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
         * tail call for the unchanged-group case. A no-op when no group has ever been selected
         * ([paginator] is null) — there is nothing to retry yet, and no [FeedUiState.Error] this
         * screen could have shown to make the retry button reachable in the first place.
         */
        fun refresh() {
            val paginator = paginator ?: return
            if (mutableState.value !is FeedUiState.Success) {
                mutableState.value = FeedUiState.Loading
            }
            viewModelScope.launch { reload(paginator) }
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
         */
        private suspend fun reload(paginator: CursorPaginator<FeedEntry>) {
            try {
                val entries = paginator.restart()
                val previous = mutableState.value as? FeedUiState.Success
                val base = previous ?: FeedUiState.Success(entries = entries)
                mutableState.value = base.copy(entries = entries, isStale = false, pageError = null)
            } catch (failure: GroupOperationException) {
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
         */
        fun loadMore() {
            val paginator = paginator ?: return
            val current = mutableState.value as? FeedUiState.Success ?: return
            if (current.loadingMore) return
            if (!paginator.hasMore.value) return
            // pageError cleared HERE, before the fetch is even launched (decision C-S: "clear the
            // error before launching a retry, not only on success") — GroupDetailViewModel's
            // loadMoreWatchlist fix round 2 lesson, applied from the start here rather than
            // rediscovered.
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    paginator.loadMore()
                    val latest = mutableState.value as? FeedUiState.Success ?: return@launch
                    mutableState.value =
                        latest.copy(entries = paginator.items.value, loadingMore = false, pageError = null)
                } catch (failure: GroupOperationException) {
                    val latest = mutableState.value as? FeedUiState.Success ?: return@launch
                    mutableState.value = latest.copy(loadingMore = false, pageError = failure.failure)
                }
            }
        }
    }
