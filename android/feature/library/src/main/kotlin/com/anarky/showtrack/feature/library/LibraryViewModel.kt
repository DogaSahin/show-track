package com.anarky.showtrack.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 * Two views live here. The default, "All shows" (`filter.status == null`), is Home: status
 * sections, each a first page, owned by [HomeSectionsController] and exposed as [home]. Picking
 * one status switches to [state]: that status's network-paged list. Only the view in front of the
 * user is loaded — Home never runs the paginator, and a single-status view never loads Home.
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

        private val homeController = HomeSectionsController(viewModelScope, repository)

        /** The "All shows" sections. Only loaded while [filter]'s status is null. */
        val home: StateFlow<HomeUiState> = homeController.state

        // Whether a full reload of the single-status list (a tab switch, a sort change, or a
        // retry) is in flight. Starts true: nothing has loaded that list yet, and a collector of
        // `state` must see Loading rather than a misleadingly empty Success.
        private val mutableLoading = MutableStateFlow(true)

        // Whether a `loadMore()` page fetch is in flight — a footer spinner under an otherwise
        // complete list, never a reason to blank the screen, hence a field on `Success`.
        private val mutableLoadingMore = MutableStateFlow(false)

        // The last failure from a full reload, or null. Deliberately a SEPARATE slot from
        // `mutableLoadMoreError` below — see `guard`'s KDoc: a full reload's failure and a page
        // fetch's failure have very different blast radii (one replaces the whole screen, the
        // other survives underneath a footer), and each one's SUCCESS must only ever clear its
        // own failure, never the other one's.
        private val mutableError = MutableStateFlow<Throwable?>(null)

        // The last failure from a `loadMore()` page fetch, or null. Feeds `Success.pageError`
        // only — it never promotes `state` to `Error`, because the entries a failed page fetch
        // left behind are still valid and still on screen.
        private val mutableLoadMoreError = MutableStateFlow<Throwable?>(null)

        // refreshOnReturn's failures land here and nowhere visible; see its KDoc.
        private val backgroundRefreshError = MutableStateFlow<Throwable?>(null)

        // Whether a `loadMore()` has appended rows since the last full reload. While it is true
        // `refreshOnReturn` stays out of the way: reloading page 1 would drop the pages below it.
        // Set only when a page actually grew the list — a short list's end trigger fires a
        // `loadMore()` on first layout that finds the list exhausted, and that must not count.
        private var loadedBeyondFirstPage = false

        /**
         * The single-status list. [LibraryRepository.observeLibrary] is network-only — the Room
         * cache belongs to Home — so there is no offline fallback here: a failed load is an
         * [LibraryUiState.Error].
         *
         * `WhileSubscribed(5_000)`: the combine is rebuilt per subscriber and bridges a
         * configuration change without holding the upstream open once the screen is gone. A
         * consequence worth knowing when testing: [state]'s `.value` will not advance past
         * `initialValue` unless something is collecting it (`collectAsStateWithLifecycle` in
         * production, Turbine or `backgroundScope.launch { state.collect {} }` in a test).
         *
         * [mutableError] takes priority over [mutableLoading] on purpose: a reload that just
         * failed always finishes by flipping `loading` back to `false` (see [guard]), and if a
         * stale `Error` outranked a fresh `false` the screen would flash back to the OLD error for
         * one frame. `applyCurrentFilter(blank = true)` clears [mutableError] before it launches
         * anything, which is what actually prevents that flash on a retry.
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

        // The header's counts ("9 shows", and the numbers in the status menu) and the "Airing soon"
        // row. Each is its own one-shot read with its own channel (decision C-S): a failure in
        // either leaves the list untouched and simply keeps the last answer, or nothing — the
        // count hides and the row stays away rather than showing "0" or an error.
        private val mutableStats = MutableStateFlow<LibraryStats?>(null)
        val stats: StateFlow<LibraryStats?> = mutableStats.asStateFlow()

        private val mutableAiringSoon = MutableStateFlow<List<LibraryEntry>>(emptyList())
        val airingSoon: StateFlow<List<LibraryEntry>> = mutableAiringSoon.asStateFlow()

        // The first load. Nothing collects [state] yet, so it blanks; the counts and the row
        // wait for the first resume ([refreshOverview]).
        init {
            reload(blank = true)
        }

        /**
         * Re-reads the counts and the "Airing soon" row, and on "All shows" reloads Home too.
         * Called on every resume by `LibraryScreen`: an add, a status change or a removal made on
         * another screen changes all three, and this ViewModel outlives that round trip. The
         * row keeps only titles with a known next episode, at most [AIRING_SOON_LIMIT]; it never
         * depends on the list's own filter or sort. The section counts come from the same stats
         * read.
         *
         * Home rides on this resume path rather than on [refreshOnReturn] because adding a title
         * no longer refreshes anything Home reads: only this reload brings it in. A single-status
         * view keeps [refreshOnReturn]'s own rules.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refreshOverview() {
            val current = mutableFilter.value
            if (current.status == null) homeController.refreshOnResume(current.sort)
            viewModelScope.launch {
                try {
                    val stats = repository.libraryStats()
                    mutableStats.value = stats
                    homeController.updateCounts(stats.bySection)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Keep the last counts; with none yet, the header simply shows no count.
                }
            }
            viewModelScope.launch {
                try {
                    mutableAiringSoon.value =
                        repository
                            .upcomingWatching(limit = AIRING_SOON_LIMIT)
                            .filter { it.media.nextEpisodeDate != null }
                            .take(AIRING_SOON_LIMIT)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Keep the last row; with none yet, the section stays hidden.
                }
            }
        }

        // `blank = true`, always — structurally required: [state]'s `combine` does not carry
        // [mutableFilter] as a source, so changing the filter ALONE would never re-evaluate it.
        // Writing to [mutableError]/[mutableLoading] (both combine sources) is what forces one;
        // without it the tab row would select the new status while the screen kept rendering the
        // old tab's rows.
        //
        // Re-picking the status already shown is a no-op: it would reload for nothing, and on
        // "All shows" mid sort change it would turn that load into a cold one and flash the cache.
        fun selectStatus(status: UserMediaStatus?) {
            if (status == mutableFilter.value.status) return
            mutableFilter.value = mutableFilter.value.copy(status = status)
            reload(blank = true)
        }

        /**
         * On "All shows" a sort change collapses every section and reloads Home under it. Re-picking
         * the sort already in effect does nothing — see [selectStatus].
         */
        fun selectSort(sort: LibrarySort) {
            if (sort == mutableFilter.value.sort) return
            mutableFilter.value = mutableFilter.value.copy(sort = sort)
            reload(blank = true)
        }

        /** "Show all" / "Show less" on one Home section. */
        fun toggleSection(section: LibrarySection) = homeController.toggle(section)

        /**
         * The Retry buttons (the stale banner, an error state): re-fetches under whatever [filter]
         * currently holds. On "All shows" it also re-reads the counts and "Airing soon": the banner
         * means the last attempt was offline, so the header counts and each section's "Show all N"
         * are as stale as the sections.
         *
         * For a single status, `blank = `[state]`.value !is `[LibraryUiState.Success]: a genuine
         * [LibraryUiState.Error] blanks to [LibraryUiState.Loading] immediately, while a list on
         * screen stays there for the round trip. Reading `state.value` is safe because `refresh()`
         * is only invoked from composition that is already collecting [state].
         */
        fun refresh() {
            reload(blank = state.value !is LibraryUiState.Success)
            // Home's load is now running under the same sort, so [refreshOverview] skips it and
            // re-reads only the counts and the row.
            if (mutableFilter.value.status == null) refreshOverview()
        }

        /**
         * A quiet reload of page 1 of the single-status list when the Library tab comes back into
         * view, so a title added, edited or reordered on another screen settles into place. Only
         * while a list is showing: before the first load that load is in charge, and during a
         * filter switch the switch is. It never shows loading, and a failure is dropped rather
         * than surfaced — a background reload failing offline must not replace a good list with
         * an error. On "All shows" it does nothing: Home reloads through [refreshOverview].
         *
         * Skipped once the user has paged past page 1 ([loadedBeyondFirstPage]): reloading page 1
         * would drop the pages they scrolled through and clamp their scroll position. Nothing is
         * lost by skipping — edits and removals are already patched into the loaded pages in
         * place, and an add refreshes the list itself.
         */
        fun refreshOnReturn() {
            if (mutableFilter.value.status == null) return
            if (state.value !is LibraryUiState.Success || loadedBeyondFirstPage) return
            guard(errorSink = backgroundRefreshError) {
                // Captured before the reload, so a load-more that fails while it is in flight
                // keeps its footer: only the failure that was already on record is cleared.
                val loadMoreErrorBefore = mutableLoadMoreError.value
                repository.applyFilter(mutableFilter.value)
                loadedBeyondFirstPage = false
                // The reload just succeeded, so an earlier load failure no longer describes the list.
                mutableError.value = null
                if (mutableLoadMoreError.value === loadMoreErrorBefore) mutableLoadMoreError.value = null
            }
        }

        /**
         * Re-entrant calls are dropped up front rather than left to [LibraryRepository]'s own
         * mutex: a `LazyColumn`'s end-reached callback fires on every frame near the bottom, and
         * without this check every one of those frames would launch its own coroutine and queue
         * up behind [com.anarky.showtrack.core.data.paging.CursorPaginator]'s lock. The flag is
         * set synchronously, before `guard` even schedules a coroutine, so two calls made back to
         * back on the same frame cannot both pass the check.
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
                    val sizeBefore = repository.observeLibrary().first().size
                    repository.loadMore()
                    if (repository.observeLibrary().first().size > sizeBefore) loadedBeyondFirstPage = true
                } finally {
                    mutableLoadingMore.value = false
                }
            }
        }

        /** Routes a load to whichever view [filter] selects. */
        private fun reload(blank: Boolean) {
            val current = mutableFilter.value
            if (current.status == null) homeController.load(current.sort) else applyCurrentFilter(blank)
        }

        /**
         * [mutableFilter] is set in [selectStatus]/[selectSort] BEFORE this runs, and stays set
         * even if the fetch below throws — unlike `LibraryRepositoryImpl.applyFilter`, which
         * reverts its OWN internal filter on failure to keep it in agreement with what
         * `CursorPaginator` actually holds. That invariant is about the repository's cursor/page
         * state; it says nothing about what the tab row should show. Reverting [mutableFilter]
         * here too would snap the selected tab back underneath an error message that never
         * explains the tab moved — indistinguishable from the tap being silently ignored.
         *
         * When [blank], [mutableError] is cleared BEFORE `loading` is set, synchronously: since
         * `error != null` outranks `loading` in [state]'s `when`, the reverse order would show the
         * identical, now-stale `ErrorState` for the whole round trip of a retry. Without [blank]
         * (a retry over a list on screen) neither combine source is touched, so [state] simply
         * does not re-emit until the retry resolves.
         *
         * [mutableLoadMoreError] is cleared unconditionally: it describes a page-fetch failure on
         * the list this call is about to REPLACE, and would otherwise survive as a stale footer.
         */
        private fun applyCurrentFilter(blank: Boolean) {
            if (blank) {
                mutableError.value = null
                mutableLoading.value = true
            }
            mutableLoadMoreError.value = null
            guard(errorSink = mutableError, trackLoading = true) {
                repository.applyFilter(mutableFilter.value)
                // Back to page 1, so the quiet reload on return is safe again.
                loadedBeyondFirstPage = false
            }
        }

        /**
         * `try`/`catch(Exception)` and not `runCatching`: runCatching swallows
         * [CancellationException] as well, which is structured concurrency's own control flow.
         * Catching Exception rather than Throwable leaves Errors (OOM, StackOverflow) alone.
         *
         * [errorSink] is which of [mutableError] / [mutableLoadMoreError] this call writes to — a
         * full reload and a page fetch must NEVER share a slot: a shared slot means a page fetch's
         * SUCCESS silently clears a full reload's error, and a page fetch's FAILURE promotes
         * `state` all the way to `Error`, discarding a fully-loaded list over one failed page.
         *
         * [trackLoading] is per-CALL: [loadMore] also runs through here but must never touch
         * `loading` — if `finally` unconditionally cleared it, a `loadMore()` resolving before a
         * concurrent filter change's fetch would turn the full-screen spinner off while that
         * reload is still in flight.
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
            const val AIRING_SOON_LIMIT = 10
        }
    }
