package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Shared by [DiscoverViewModelTest] and [DiscoverResumeTest] (task 9c.8, E-M) — [FakeRecommendationRepository]'s
 * own KDoc explains why this was extracted from a private nested class into its own file.
 *
 * Only [add] is functional — the single [LibraryRepository] call [DiscoverViewModel] ever makes.
 * Every other member `error(...)`s rather than silently no-op-ing: a [DiscoverViewModel] that
 * accidentally reached one of them fails LOUDLY, with that message, rather than an unexplained
 * result — `:feature:favorites`' own `FakeLibraryRepository`'s identical discrimination technique.
 *
 * [addGate], when set, lets a test hold [add] in flight while it drives other ViewModel actions —
 * `SearchViewModelTest`'s `FakeLibraryRepository` identical technique.
 */
internal class FakeLibraryRepository(
    var addFailure: Throwable? = null,
    private val addGate: CompletableDeferred<Unit>? = null,
) : LibraryRepository {
    val addCalls = mutableListOf<Pair<MediaSource, String>>()

    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by DiscoverViewModel")

    override suspend fun refresh(): Unit = error("not exercised by DiscoverViewModel")

    override suspend fun loadMore(): Unit = error("not exercised by DiscoverViewModel")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by DiscoverViewModel")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry {
        addCalls += source to externalId
        addGate?.await()
        addFailure?.let { throw it }
        return DUMMY_ENTRY
    }

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("not exercised by DiscoverViewModel")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by DiscoverViewModel")

    override val favoriteEntries: StateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())

    override suspend fun refreshFavorites(): Unit = error("not exercised by DiscoverViewModel")

    override suspend fun loadMoreFavorites(): Unit = error("not exercised by DiscoverViewModel")

    override suspend fun libraryStats() = error("not exercised by DiscoverViewModel")

    override suspend fun importAniList(username: String) = error("not exercised by DiscoverViewModel")

    private companion object {
        val DUMMY_ENTRY =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.PLANNED,
                score = null,
                progress = 0,
                favorite = false,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media =
                    Media(
                        id = "m-1",
                        source = MediaSource.ANILIST,
                        externalId = "21",
                        type = MediaType.ANIME,
                        title = "One Piece",
                        year = 1999,
                        genres = listOf("Action"),
                        coverImageUrl = null,
                        status = MediaStatus.AIRING,
                        nextEpisodeSeason = null,
                        nextEpisodeNumber = 1100,
                        nextEpisodeDate = Instant.parse("2026-09-01T00:00:00Z"),
                        daysUntilNextEpisode = 4,
                    ),
            )
    }
}
