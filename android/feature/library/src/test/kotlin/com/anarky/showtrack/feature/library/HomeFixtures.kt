package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import java.time.Instant

/** Entries, pages and stats for the Home tests ([HomeSectionsTest], [HomeSectionsReloadTest]). */
internal object HomeFixtures {
    /** Single-page sections (no cursor). */
    fun pages(vararg sections: Pair<LibrarySection, List<LibraryEntry>>): Map<LibrarySection, Page<LibraryEntry>> =
        sections.associate { (section, items) -> section to Page(items, nextCursor = null) }

    fun entries(
        prefix: String,
        section: LibrarySection,
        count: Int,
        cursor: String? = null,
    ): Page<LibraryEntry> = Page((1..count).map { entry("$prefix$it", section) }, nextCursor = cursor)

    fun stats(vararg bySection: Pair<LibrarySection, Int>) =
        LibraryStats(
            total = bySection.sumOf { it.second },
            byStatus = emptyMap(),
            bySection = bySection.toMap(),
            averageScore = null,
            ratedCount = 0,
            episodesWatched = 0,
            topGenres = emptyList(),
            addedThisMonth = 0,
            favorites = 0,
        )

    fun entry(
        id: String,
        section: LibrarySection,
    ) = LibraryEntry(
        id = id,
        status = UserMediaStatus.WATCHING,
        score = null,
        progress = 0,
        favorite = false,
        updatedAt = Instant.parse("2026-10-01T00:00:00Z"),
        media =
            Media(
                id = "media-$id",
                source = MediaSource.TMDB,
                externalId = id,
                type = MediaType.TV,
                title = id,
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
                status = MediaStatus.AIRING,
                nextEpisodeSeason = null,
                nextEpisodeNumber = null,
                nextEpisodeDate = null,
                daysUntilNextEpisode = null,
            ),
        section = section,
    )
}
