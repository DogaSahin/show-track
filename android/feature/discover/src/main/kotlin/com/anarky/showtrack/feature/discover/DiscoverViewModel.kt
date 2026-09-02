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

        init {
            refresh()
        }

        /**
         * The initial load, and the only operation allowed to replace [state] with
         * [DiscoverUiState.Error] wholesale — see [DiscoverUiState]'s KDoc for why that is safe
         * here. [DiscoverUiState.Loading] is written SYNCHRONOUSLY before the coroutine is even
         * launched (decision C-S: clear the error before launching a retry, not only on success),
         * so a retry from [DiscoverUiState.Error] does not leave the OLD error on screen for the
         * round trip's whole duration — `SearchViewModel.runSearch`'s same discipline.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refresh() {
            mutableState.value = DiscoverUiState.Loading
            viewModelScope.launch {
                try {
                    recommendationRepository.refresh()
                    mutableState.value = DiscoverUiState.Success(items = recommendationRepository.feed.value)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.value = DiscoverUiState.Error(failure)
                }
            }
        }

        /**
         * Re-entrant calls are dropped up front, the same guard `LibraryViewModel.loadMore`/
         * `SearchViewModel.loadMore` use: a `LazyColumn`'s end-reached callback fires on every
         * frame near the bottom, and without this a scroll near the bottom would queue up a fetch
         * per frame.
         *
         * Routed through [DiscoverUiState.Success.pageError], never [DiscoverUiState.Error]: the
         * rows a failed page-2 fetch left behind are still valid and still on screen.
         */
        @Suppress("TooGenericExceptionCaught")
        fun loadMore() {
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
         * even starts — and [libraryRepository].add is called with its `(source, externalId)`.
         *
         * On failure, the row is put back **at its original index**, never merely appended back to
         * the end: [originalIndex] is captured before the removal, and restoring anywhere else
         * would visibly reshuffle the list the user is still looking at over an error that has
         * nothing to do with ordering. The index is `coerceIn`-clamped against the CURRENT list
         * size when restoring, not blindly reused: a `loadMore()` completing while this add was in
         * flight can only have grown [DiscoverUiState.Success.items] by appending past the removal
         * point (never inserting before it — `RecommendationRepository.loadMore` only appends), so
         * the original index is always still a valid insertion point for what was removed from it;
         * the clamp is defensive rather than load-bearing for that specific race, and exists so a
         * future change to that assumption fails safe (an inserted row at the wrong end) rather
         * than by throwing.
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
                success.copy(
                    items = success.items.filterNot { it.media.id == recommendation.media.id },
                    addError = null,
                )
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
                    replaceSuccess { success ->
                        val restored =
                            success.items.toMutableList().apply {
                                add(originalIndex.coerceIn(0, size), recommendation)
                            }
                        success.copy(items = restored, addError = AddFailure(recommendation.media.id, failure))
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
