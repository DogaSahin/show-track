package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.model.Recommendation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared by [DiscoverViewModelTest] and [DiscoverResumeTest] (task 9c.8, E-M) — both exercise
 * [DiscoverViewModel] against a fake rather than
 * [com.anarky.showtrack.core.data.repository.RecommendationRepositoryImpl], which would need
 * Retrofit, neither on this module's compile classpath (architecture rule 2). Extracted from a
 * private nested class of [DiscoverViewModelTest] into its own file the same way
 * `:feature:favorites`' `FakeLibraryRepository` was, for the identical reason: a nested `private
 * class` is invisible outside its enclosing test class, and [DiscoverResumeTest] needs the exact
 * same fake to drive the same ViewModel through a real `Lifecycle`.
 *
 * [refreshGate], when set, is what lets a test observe [DiscoverViewModel.state] WHILE [refresh]
 * is still suspended, rather than only before and after — `FavoritesViewModelTest`'s
 * `FakeLibraryRepository.refreshGate`'s identical technique, needed for the identical reason: a
 * fake that always resolves synchronously can never make a wrongly-shown [DiscoverUiState.Loading]
 * (on a resume) or a re-entrant second call's rejection observable mid-flight.
 */
internal class FakeRecommendationRepository(
    var refreshResult: List<Recommendation> = emptyList(),
    var refreshFailure: Throwable? = null,
    var loadMoreAppends: List<Recommendation> = emptyList(),
    var loadMoreFailure: Throwable? = null,
) : RecommendationRepository {
    private val mutableFeed = MutableStateFlow<List<Recommendation>>(emptyList())
    override val feed: StateFlow<List<Recommendation>> = mutableFeed.asStateFlow()

    var refreshGate: CompletableDeferred<Unit>? = null

    // Set by a test that needs loadMore() to still be genuinely in flight (loadingMore == true)
    // while it drives a concurrent refresh() — round 1's B2/B3 regression coverage.
    var loadMoreGate: CompletableDeferred<Unit>? = null

    var refreshCalls = 0
        private set
    var loadMoreCalls = 0
        private set
    val removedIds = mutableListOf<String>()

    override suspend fun refresh() {
        refreshCalls++
        refreshGate?.await()
        refreshFailure?.let { throw it }
        mutableFeed.value = refreshResult
    }

    override suspend fun loadMore() {
        loadMoreCalls++
        loadMoreGate?.await()
        loadMoreFailure?.let { throw it }
        mutableFeed.value = mutableFeed.value + loadMoreAppends
    }

    override fun remove(mediaId: String) {
        removedIds += mediaId
        mutableFeed.value = mutableFeed.value.filterNot { it.media.id == mediaId }
    }

    override fun restore(
        index: Int,
        recommendation: Recommendation,
    ) {
        mutableFeed.value =
            mutableFeed.value.toMutableList().apply {
                add(index.coerceIn(0, size), recommendation)
            }
    }
}
