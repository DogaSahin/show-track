package com.anarky.showtrack.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.data.search.RecentSearchStore
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Finds titles, opens them, and adds them.
 *
 * Tapping a result OPENS it and never adds it: a stored title opens straight away by its
 * `mediaId`, anything else is resolved first. Adding is its own action, which keeps the user on
 * Search and turns the row's button into the status chip.
 *
 * One class for the screen's one seam (search, open, add, recent searches all act on the same
 * result list), hence the `TooManyFunctions` suppression the other screen ViewModels carry too.
 *
 * `state` is a plain [MutableStateFlow]: every change to it comes from a call this ViewModel makes
 * itself, so there is no background writer to gate a subscription against.
 */
@Suppress("TooManyFunctions")
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
        private val libraryRepository: LibraryRepository,
        private val recentSearchStore: RecentSearchStore,
    ) : ViewModel() {
        private val mutableQuery = MutableStateFlow("")
        val query: StateFlow<String> = mutableQuery.asStateFlow()

        private val mutableState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
        val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

        val recentSearches: StateFlow<List<String>> =
            recentSearchStore.recent
                .catch { emit(emptyList()) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // A Channel rather than a StateFlow: each event reaches exactly one collector, once, so a
        // rotation does not navigate or show a snackbar a second time.
        private val eventChannel = Channel<SearchEvent>(Channel.BUFFERED)
        val events: Flow<SearchEvent> = eventChannel.receiveAsFlow()

        // A query already searched (a recent search run) or deliberately not searched (one put in
        // the field with ↖). The debounced pipeline skips it; the next keystroke clears it.
        private var skipQuery: String? = null

        // What this screen learned after the results came back: an add's entry, a resolve's id.
        // Applied over every list it publishes until the next search brings the server's own view.
        private val patches = mutableMapOf<String, ResultPatch>()

        private var addInFlight: String? = null
        private var openInFlight: String? = null
        private var lastOpenAtNanos: Long? = null

        // One search at a time: starting another cancels the one in flight, so two can never race
        // to publish (or to set the repository's query).
        private var searchJob: Job? = null

        // The query whose results are on screen or loading, so retyping it is not a new search.
        private var shownQuery: String? = null

        init {
            viewModelScope.launch {
                mutableQuery
                    .debounce(SEARCH_DEBOUNCE_MS)
                    .filter { it.isNotBlank() && it != skipQuery && !isShowing(it) }
                    .collect { startSearch(it) }
            }
        }

        /**
         * Typing. Written synchronously so the field never lags; a blank query shows the recent
         * searches at once rather than after the debounce.
         */
        fun onQueryChange(newQuery: String) {
            skipQuery = null
            mutableQuery.value = newQuery
            if (newQuery.isBlank()) {
                searchJob?.cancel()
                shownQuery = null
                mutableState.value = SearchUiState.Idle
            }
        }

        /**
         * The keyboard's search key: saves the query, and searches now unless that query's results
         * are already showing or loading (a filled-in recent search, or one that failed, has none).
         */
        fun onSubmit() {
            val current = mutableQuery.value
            if (current.isBlank()) return
            record(current)
            if (!isShowing(current)) {
                skipQuery = current
                startSearch(current)
            }
        }

        /** A recent search tapped: runs it now, and moves it to the top. */
        fun runRecent(recent: String) {
            skipQuery = recent
            mutableQuery.value = recent
            record(recent)
            startSearch(recent)
        }

        /** ↖ on a recent search: puts it in the field to edit, without searching. */
        fun fillQuery(recent: String) {
            skipQuery = recent
            mutableQuery.value = recent
        }

        fun clearRecentSearches() {
            viewModelScope.launch {
                runCatchingStore { recentSearchStore.clear() }
            }
        }

        /** Re-runs the current query: the only operation allowed to replace [state] with an error. */
        fun retry() {
            val current = mutableQuery.value
            if (current.isNotBlank()) startSearch(current)
        }

        /** Re-entrant calls are dropped: the end-of-list trigger can fire on several frames. */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
            val current = mutableState.value as? SearchUiState.Success ?: return
            if (current.loadingMore) return
            mutableState.value = current.copy(loadingMore = true, pageError = null)
            viewModelScope.launch {
                try {
                    mediaRepository.loadMoreResults()
                    replaceSuccess {
                        it.copy(results = patched(mediaRepository.searchResults.value), loadingMore = false)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    replaceSuccess { it.copy(loadingMore = false, pageError = failure) }
                }
            }
        }

        /**
         * Opens a result's details. Never adds it to the library. A result with no stored row is
         * resolved first; while that runs a second open is ignored, and a failure leaves the row as
         * it was and reports [SearchEvent.OpenFailed].
         */
        @Suppress("TooGenericExceptionCaught")
        fun open(result: SearchResult) {
            // One title at a time: a double tap, or a second row tapped while one resolves, would
            // otherwise push two detail screens.
            if (openInFlight != null || openedJustNow()) return
            record(mutableQuery.value)
            val mediaId = result.mediaId
            if (mediaId != null) {
                lastOpenAtNanos = System.nanoTime()
                eventChannel.trySend(SearchEvent.OpenDetail(mediaId))
                return
            }
            val key = result.media.key
            openInFlight = key
            replaceSuccess { it.copy(opening = key) }
            viewModelScope.launch {
                try {
                    val media =
                        mediaRepository.resolve(
                            source = result.media.source,
                            externalId = result.media.externalId,
                        )
                    patch(key, ResultPatch(mediaId = media.id, status = null))
                    lastOpenAtNanos = System.nanoTime()
                    eventChannel.trySend(SearchEvent.OpenDetail(media.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    eventChannel.trySend(SearchEvent.OpenFailed)
                } finally {
                    openInFlight = null
                    replaceSuccess { it.copy(opening = null) }
                }
            }
        }

        /**
         * Adds a result as Planned and stays on Search: the row turns into the status chip and
         * [SearchEvent.Added] offers to open it. One add at a time; the endpoint is idempotent, so
         * adding a title already tracked is harmless.
         *
         * A failure may still mean the title was added (the library refresh after a successful
         * POST can fail on its own), which is why the failure copy does not claim otherwise.
         */
        @Suppress("TooGenericExceptionCaught")
        fun add(result: SearchResult) {
            if (addInFlight != null) return
            val key = result.media.key
            addInFlight = key
            replaceSuccess { it.copy(adding = key) }
            viewModelScope.launch {
                try {
                    val entry =
                        libraryRepository.add(
                            source = result.media.source,
                            externalId = result.media.externalId,
                        )
                    patch(key, ResultPatch(mediaId = entry.media.id, status = entry.status))
                    eventChannel.trySend(SearchEvent.Added(title = result.media.title, mediaId = entry.media.id))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    eventChannel.trySend(SearchEvent.AddFailed)
                } finally {
                    // On every exit, cancellation included, or the row would keep its spinner.
                    addInFlight = null
                    replaceSuccess { it.copy(adding = null) }
                }
            }
        }

        /**
         * Set to [SearchUiState.Loading] synchronously, so a retry never leaves the old error up for
         * the round trip. Every later write checks the query is still this one: a cleared or changed
         * field makes a superseded search a no-op rather than a race.
         */
        private fun startSearch(searchQuery: String) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(searchQuery) }
        }

        // Loading counts only while a search is really running: one that was superseded returns
        // without publishing, and its Loading must not block the same query from running again.
        private fun isShowing(searchQuery: String): Boolean =
            searchQuery == shownQuery &&
                when (mutableState.value) {
                    is SearchUiState.Success -> true
                    is SearchUiState.Loading -> searchJob?.isActive == true
                    else -> false
                }

        private fun openedJustNow(): Boolean =
            lastOpenAtNanos?.let { System.nanoTime() - it < OPEN_GUARD_NANOS } ?: false

        @Suppress("TooGenericExceptionCaught")
        private suspend fun runSearch(searchQuery: String) {
            if (mutableQuery.value != searchQuery) return
            shownQuery = searchQuery
            mutableState.value = SearchUiState.Loading
            try {
                mediaRepository.search(searchQuery)
                if (mutableQuery.value != searchQuery) return
                // The server's view of this new list is current; what earlier taps learned is not
                // needed any more, except for an add still in flight, which patches when it lands.
                patches.clear()
                mutableState.value = SearchUiState.Success(results = mediaRepository.searchResults.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (mutableQuery.value != searchQuery) return
                mutableState.value = SearchUiState.Error(failure)
            }
        }

        private fun patch(
            key: String,
            patch: ResultPatch,
        ) {
            patches[key] = patch
            replaceSuccess { it.copy(results = patched(it.results)) }
        }

        private fun patched(results: SearchResults): SearchResults =
            if (patches.isEmpty()) {
                results
            } else {
                results.copy(
                    items =
                        results.items.map { result ->
                            val patch = patches[result.media.key] ?: return@map result
                            result.copy(mediaId = patch.mediaId, libraryStatus = patch.status ?: result.libraryStatus)
                        },
                )
            }

        private fun record(query: String) {
            if (query.isBlank()) return
            viewModelScope.launch { runCatchingStore { recentSearchStore.record(query) } }
        }

        /** Recent searches are a convenience: a failed local write is never worth an error. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runCatchingStore(block: suspend () -> Unit) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Nothing to show: the list simply stays as it was.
            }
        }

        private inline fun replaceSuccess(transform: (SearchUiState.Success) -> SearchUiState.Success) {
            val latest = mutableState.value as? SearchUiState.Success ?: return
            mutableState.value = transform(latest)
        }

        private data class ResultPatch(
            val mediaId: String,
            val status: UserMediaStatus?,
        )

        private companion object {
            const val SEARCH_DEBOUNCE_MS = 300L

            // Long enough to swallow a double tap, short enough that coming back and opening
            // another title is never refused.
            const val OPEN_GUARD_NANOS = 600_000_000L
        }
    }
