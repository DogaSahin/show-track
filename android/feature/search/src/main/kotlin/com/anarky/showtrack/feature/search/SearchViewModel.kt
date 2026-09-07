package com.anarky.showtrack.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.MediaSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Finds a title and adds it — the only way a title first enters a user's library (Task 9a.10).
 *
 * The constructor names two INTERFACES from `:core:data` and nothing else, same shape as
 * `LibraryViewModel`/`DetailViewModel` — architecture rule 2, structural rather than a review item.
 *
 * **`state` is a plain [MutableStateFlow], not `combine(...).stateIn(WhileSubscribed(5_000))` the
 * way `LibraryViewModel.state` is — `DetailViewModel`'s shape, not `LibraryViewModel`'s.**
 * `WhileSubscribed` exists on the library screen to stop a continuously-updating, Room-backed
 * [Flow] from being re-collected (and its query re-run) for a screen nobody is watching. Nothing
 * here is like that: [MediaRepository.search], [MediaRepository.loadMoreResults] and
 * [LibraryRepository.add] are one-shot suspend calls THIS ViewModel drives itself, and
 * [MediaRepository.searchResults] changes only in response to the two calls this ViewModel itself
 * makes — there is no independent background writer to gate a subscription against, so `combine`
 * would add the `WhileSubscribed` testing gotcha (carried-forward note 3: a bare `.value` read
 * never advances past `initialValue` without an active collector) for no correctness benefit.
 *
 * **Three operations, three failure channels — carried forward from task 9a.8's shipped bug,
 * which is exactly this screen's shape.** A single shared error slot there let a failed page-2
 * fetch discard a fully-populated screen. Here:
 * - A fresh **search** (query change, or [retry]) is the only thing that may replace [state] with
 *   [SearchUiState.Error] wholesale — see [SearchUiState]'s KDoc for why that is safe.
 * - [loadMore] never touches [SearchUiState.Error]; a failure is written to
 *   [SearchUiState.Success.pageError], leaving [SearchUiState.Success.results] exactly as it was.
 * - [add] never touches [SearchUiState.Error] either; a failure is written to
 *   [SearchUiState.Success.addError], tagged with the row it was for, again leaving `results`
 *   untouched. [add]'s success clears only [SearchUiState.Success.addError], never
 *   [SearchUiState.Success.pageError] and vice versa — the two channels are independent slots, not
 *   one shared one (see [replaceSuccess] call sites).
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        private val mutableQuery = MutableStateFlow("")
        val query: StateFlow<String> = mutableQuery.asStateFlow()

        private val mutableState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
        val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

        // `receiveAsFlow`, not a `StateFlow<String?>`: a StateFlow replays its last value to every
        // new collector, including the one a configuration change creates — which would navigate
        // to the same title again on rotation. A Channel delivers each id to exactly one collector,
        // once, and holds nothing afterwards for a later subscriber to replay.
        private val navigateChannel = Channel<String>(Channel.BUFFERED)
        val navigateToDetail: Flow<String> = navigateChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                mutableQuery
                    .debounce(SEARCH_DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .filter { it.isNotBlank() }
                    .collect { runSearch(it) }
            }
        }

        /**
         * [newQuery] is written synchronously so the field itself never lags behind what was
         * typed. A blank query short-circuits straight to [SearchUiState.Idle] rather than waiting
         * out the debounce window and then being filtered — the field going blank should read as
         * "nothing to show" immediately, not after a 300ms pause that looks like nothing happened.
         */
        fun onQueryChange(newQuery: String) {
            mutableQuery.value = newQuery
            if (newQuery.isBlank()) mutableState.value = SearchUiState.Idle
        }

        /** Re-runs the CURRENT query — the only operation allowed to replace [state] with [SearchUiState.Error]. */
        fun retry() {
            val current = mutableQuery.value
            if (current.isNotBlank()) viewModelScope.launch { runSearch(current) }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore` uses:
         * a `LazyColumn`'s end-reached callback fires on every frame near the bottom, and without
         * this a scroll near the bottom would queue up a fetch per frame.
         *
         * Routed through [SearchUiState.Success.pageError], never [SearchUiState.Error]: the
         * results a failed page-2 fetch left behind are still valid and still on screen.
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            val current = mutableState.value as? SearchUiState.Success ?: return
            if (current.loadingMore) return
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    mediaRepository.loadMoreResults()
                    replaceSuccess {
                        it.copy(results = mediaRepository.searchResults.value, loadingMore = false, pageError = null)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(loadingMore = false, pageError = failure) }
                }
            }
        }

        // Re-entrancy state for [add], held here rather than read off `SearchUiState.Success.adding`:
        // a debounced search resolving mid-add replaces `state` with a FRESH `Success` whose
        // `adding` defaults back to null (carried-forward review note — see task brief), so a
        // guard reading `current.adding` would pass a second tap while the first add is still in
        // flight and fire two POSTs plus two `navigateChannel.trySend`s. A ViewModel field
        // survives that state replacement because it isn't part of the state's shape at all.
        private var addInFlight: String? = null

        /**
         * A search result carries no id (decision C-N): tapping one ADDS it, then navigates to the
         * detail screen using the id `POST /v1/library` hands back in its response — never an id
         * the client already had, because none exists until this call creates the row.
         *
         * A second tap on ANY row while one add is already in flight is dropped (same re-entrancy
         * shape as [loadMore] and `DetailViewModel.edit`) rather than letting two adds race. That
         * guard is [addInFlight], a plain ViewModel field — **not** scoped to [SearchUiState.Success],
         * unlike the `adding`/`addError` bookkeeping [replaceSuccess] applies, which still only
         * touches a `Success` because a row can only be TAPPED while its list is on screen. This is
         * a deliberate widening past the reported bug: the previous guard read
         * `(state as? Success)?.adding`, so a caller invoking [add] while [state] was NOT `Success`
         * skipped the check entirely and had no upper bound on concurrent in-flight adds at all. A
         * field the guard can always read, regardless of [state]'s shape, closes that instead of
         * only the narrower "debounced search resets `adding` mid-add" case that was reported.
         *
         * A failure here may still mean the title was added: [LibraryRepository.add] refreshes the
         * library after a successful `POST`, and that refresh can throw on its own even though the
         * row was created (`LibraryRepositoryImpl.add`'s documented wrinkle, carried forward from
         * task 9a.5's review). So this never claims the add failed outright — [SearchUiState.Success.addError]
         * carries the raw cause and the screen's copy is worded for what is actually known; the
         * endpoint is idempotent, so retrying (tapping the same row again) is always safe.
         */
        @Suppress("TooGenericExceptionCaught")
        fun add(summary: MediaSummary) {
            if (addInFlight != null) return
            addInFlight = summary.externalId
            replaceSuccess { it.copy(adding = summary.externalId, addError = null) }
            viewModelScope.launch {
                try {
                    val entry = libraryRepository.add(source = summary.source, externalId = summary.externalId)
                    replaceSuccess { it.copy(adding = null, addError = null) }
                    navigateChannel.trySend(entry.media.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(adding = null, addError = AddFailure(summary.externalId, failure)) }
                } finally {
                    // Clearing here, not in each branch: a CancellationException from INSIDE
                    // libraryRepository.add (a timeout, a cancelled inner scope) would otherwise
                    // rethrow past both `catch`es without ever clearing addInFlight, permanently
                    // disabling this row's add affordance for the rest of the ViewModel's life with
                    // no error shown. `finally` runs on every exit path, cancellation included.
                    addInFlight = null
                    // Symmetric with addInFlight above: the success/failure branches already clear
                    // Success.adding themselves, so this is a no-op there. On the cancellation path
                    // neither branch runs, and without this the row would keep its spinner and stay
                    // `enabled = false` forever with no error shown — the guard released but the
                    // state never caught up to it.
                    replaceSuccess { it.copy(adding = null) }
                }
            }
        }

        /**
         * Set synchronously before a coroutine is even launched: a retry from [SearchUiState.Error]
         * must not leave the OLD error on screen for the round trip's whole duration
         * (carried-forward note 2). Replacing the entire state with [SearchUiState.Loading] —
         * rather than flipping a field — clears it immediately by construction.
         *
         * Every write below is guarded by `mutableQuery.value != searchQuery`: [onQueryChange]'s
         * clear-the-field path sets [SearchUiState.Idle] synchronously and does NOT go through the
         * `debounce`/`distinctUntilChanged` collector in `init`, so an in-flight [runSearch] for a
         * query the user has since cleared (or changed again, via [retry] racing a fresh keystroke)
         * is not otherwise serialised against it — it would resolve later and overwrite `Idle` (or
         * a newer `Success`/`Error`) with stale results. Checking [searchQuery] against the CURRENT
         * [mutableQuery] value at each write — not `collectLatest` in `init`, which would cancel the
         * repository call mid-write instead of merely discarding its result — is what makes a
         * superseded search a no-op rather than a race.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSearch(searchQuery: String) {
            if (mutableQuery.value != searchQuery) return
            mutableState.value = SearchUiState.Loading
            try {
                mediaRepository.search(searchQuery)
                if (mutableQuery.value != searchQuery) return
                mutableState.value = SearchUiState.Success(results = mediaRepository.searchResults.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (mutableQuery.value != searchQuery) return
                mutableState.value = SearchUiState.Error(failure)
            }
        }

        private inline fun replaceSuccess(transform: (SearchUiState.Success) -> SearchUiState.Success) {
            val latest = mutableState.value as? SearchUiState.Success ?: return
            mutableState.value = transform(latest)
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MS = 300L
        }
    }
