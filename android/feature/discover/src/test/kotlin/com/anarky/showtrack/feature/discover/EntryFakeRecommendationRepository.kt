package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.model.Recommendation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A SEPARATE fake from [DiscoverViewModelTest]'s own private `FakeRecommendationRepository` — that
 * one is `private` to its file, so a `@BindValue` field in [DiscoverEntryHiltTest] cannot reach it.
 * [refreshResult] must be set BEFORE Hilt injects this: `DiscoverViewModel.init { refresh() }` runs
 * the instant `hiltViewModel()` constructs the ViewModel, which happens the moment `discoverEntry()`
 * first composes — there is no later hook for a test to seed data into an already-constructed fake.
 */
internal class EntryFakeRecommendationRepository(
    var refreshResult: List<Recommendation> = emptyList(),
) : RecommendationRepository {
    private val mutableFeed = MutableStateFlow<List<Recommendation>>(emptyList())
    override val feed: StateFlow<List<Recommendation>> = mutableFeed.asStateFlow()

    override suspend fun refresh() {
        mutableFeed.value = refreshResult
    }

    override suspend fun loadMore() = Unit

    override fun remove(mediaId: String) {
        mutableFeed.value = mutableFeed.value.filterNot { it.media.id == mediaId }
    }

    override fun restore(
        index: Int,
        recommendation: Recommendation,
    ) {
        mutableFeed.value = mutableFeed.value.toMutableList().apply { add(index.coerceIn(0, size), recommendation) }
    }
}
