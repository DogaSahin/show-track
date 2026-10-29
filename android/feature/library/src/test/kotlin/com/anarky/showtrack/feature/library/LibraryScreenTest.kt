package com.anarky.showtrack.feature.library

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/**
 * Gap 1 (Phase 9a device walkthroughs): `:feature:search` was fully built and completely
 * unreachable — nothing in the app ever navigated to it. This is the regression guard for the
 * door this task adds: the header's search action must actually invoke [onSearchClick] rather
 * than merely compile and sit there, which is exactly the class of bug a Compose test can catch
 * that reading the source cannot.
 *
 * Drives the stateless overload directly — `LibraryScreen`'s own KDoc calls out that split as
 * "previewable and testable with no nav graph, [no] ViewModel". `createComposeRule`, not
 * `createAndroidComposeRule`: no Activity is needed to render this composable in isolation.
 * Robolectric supplies the Android runtime `stringResource`/`painterResource` need; `sdk = 35` is
 * pinned module-wide in `src/test/resources/robolectric.properties` — Robolectric ships no shadow
 * jar for 36. No `application` override: unlike `:app`, this library module's own manifest names
 * no `Application` class, so the default test application is already enough — the same setup
 * `:feature:profile`'s `EpisodeAlertNotifierTest` uses, and the same reasoning `:core:designsystem`'s
 * `StatusPresentationTest` gives for relying on the properties file alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping the search action invokes onSearchClick`() {
        var searchClicked = false

        composeRule.setContent {
            LibraryScreen(
                home = HomeUiState.Ready(sections = emptyList(), isStale = false),
                state = LibraryUiState.Success(entries = emptyList(), loadingMore = false),
                filter = LibraryFilter(),
                stats = null,
                airingSoon = emptyList(),
                onStatusSelected = {},
                onSortSelected = {},
                onToggleSection = {},
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSearchClick = { searchClicked = true },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.library_search_content_description))
            .performClick()

        assertTrue(searchClicked)
    }

    @Test
    fun `the count shows once stats arrive, for the selected status`() {
        setScreen(filter = LibraryFilter(status = UserMediaStatus.WATCHING), stats = STATS)

        composeRule.onNodeWithText("3 shows").assertIsDisplayed()
    }

    @Test
    fun `no stats means no count, never zero`() {
        setScreen(stats = null)

        composeRule.onNodeWithText("0 shows").assertDoesNotExist()
        composeRule.onNodeWithText("9 shows").assertDoesNotExist()
    }

    @Test
    fun `the filter buttons say what they filter to a screen reader`() {
        setScreen(filter = LibraryFilter(status = UserMediaStatus.WATCHING, sort = LibrarySort.NEXT_EPISODE_DATE))

        composeRule.onNodeWithContentDescription("Status: Watching").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sort: Next episode").assertIsDisplayed()
    }

    @Test
    fun `picking a status from the menu selects it`() {
        var picked: UserMediaStatus? = null
        setScreen(onStatusSelected = { picked = it })

        composeRule.onNodeWithContentDescription("Status: All shows").performClick()
        composeRule.onNodeWithText("Planned").performClick()

        assertEquals(UserMediaStatus.PLANNED, picked)
    }

    @Test
    fun `airing soon shows on all shows`() {
        val upcoming = ENTRY.copy(id = "entry-2", media = ENTRY.media.copy(id = "media-2", title = "Dandadan"))
        setScreen(home = ready(section(LibrarySection.WATCHING, listOf(ENTRY))), airingSoon = listOf(upcoming))
        composeRule.onNodeWithText("Airing soon").assertIsDisplayed()
        composeRule.onNodeWithText("Dandadan").assertIsDisplayed()
    }

    @Test
    fun `under a status filter the airing row is not shown`() {
        val upcoming = ENTRY.copy(id = "entry-2", media = ENTRY.media.copy(id = "media-2", title = "Dandadan"))
        setScreen(
            filter = LibraryFilter(status = UserMediaStatus.WATCHING),
            entries = listOf(ENTRY),
            airingSoon = listOf(upcoming),
        )

        composeRule.onNodeWithText("Airing soon").assertDoesNotExist()
    }

    @Test
    fun `an empty status filter offers to show all`() {
        var picked: UserMediaStatus? = UserMediaStatus.DROPPED
        setScreen(filter = LibraryFilter(status = UserMediaStatus.DROPPED), onStatusSelected = { picked = it })

        composeRule.onNodeWithText("Show all").performClick()

        assertEquals(null, picked)
    }

    @Test
    fun `home shows section headers in order with counts`() {
        setScreen(
            home =
                ready(
                    section(LibrarySection.WATCHING, entries("w", 2), count = 2),
                    section(LibrarySection.PLANNED, entries("p", 3), count = 3),
                    section(LibrarySection.DROPPED, entries("d", 1), count = null),
                ),
        )

        val headers =
            composeRule
                .onAllNodesWithTag(SECTION_HEADER_TAG)
                .fetchSemanticsNodes()
                .map { node -> node.config[SemanticsProperties.Text].joinToString(" ") { it.text } }
        // A count the server has not reported yet is left out, never shown as the loaded size.
        assertEquals(listOf("Watching 2", "Planned 3", "Dropped"), headers)
    }

    @Test
    fun `section with more than twelve shows a Show all button and only twelve posters`() {
        setScreen(home = ready(section(LibrarySection.PLANNED, entries("p", 13), count = 13)))

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Show all 13"))
        composeRule.onNodeWithText("Show p12").assertExists()
        composeRule.onNodeWithText("Show p13").assertDoesNotExist()
    }

    @Test
    fun `a section of twelve or fewer has no button`() {
        setScreen(home = ready(section(LibrarySection.PLANNED, entries("p", 12), count = 12)))

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Show p12"))
        composeRule.onNodeWithText("Show all 12").assertDoesNotExist()
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    @Test
    fun `tapping Show all calls toggleSection`() {
        var toggled: LibrarySection? = null
        setScreen(
            home = ready(section(LibrarySection.PLANNED, entries("p", 13), count = 20)),
            onToggleSection = { toggled = it },
        )

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Show all 20"))
        composeRule.onNodeWithText("Show all 20").performClick()

        assertEquals(LibrarySection.PLANNED, toggled)
    }

    @Test
    fun `expanded section shows Show less`() {
        var toggled: LibrarySection? = null
        setScreen(
            home = ready(section(LibrarySection.PLANNED, entries("p", 13), count = 13, expanded = true)),
            onToggleSection = { toggled = it },
        )

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Show less"))
        composeRule.onNodeWithText("Show p13").assertExists()
        composeRule.onNodeWithText("Show less").performClick()

        assertEquals(LibrarySection.PLANNED, toggled)
    }

    @Test
    fun `caught up poster shows S2 soon when the next season is known`() {
        val caughtUp =
            ENTRY.copy(
                id = "c1",
                section = LibrarySection.CAUGHT_UP,
                media = ENTRY.media.copy(id = "mc1", title = "Frieren", nextEpisodeSeason = 2),
            )
        setScreen(home = ready(section(LibrarySection.WATCHING, listOf(caughtUp))))

        composeRule.onNodeWithText("S2 soon").assertIsDisplayed()
    }

    @Test
    fun `caught up poster shows More soon when nothing is scheduled`() {
        val caughtUp =
            ENTRY.copy(
                id = "c1",
                section = LibrarySection.CAUGHT_UP,
                media =
                    ENTRY.media.copy(
                        id = "mc1",
                        title = "Frieren",
                        nextEpisodeSeason = 1,
                        nextEpisodeNumber = null,
                        nextEpisodeDate = null,
                        daysUntilNextEpisode = null,
                    ),
            )
        // A watching show with an episode coming carries no label at all.
        val watching = ENTRY.copy(section = LibrarySection.WATCHING, media = ENTRY.media.copy(nextEpisodeSeason = 1))
        setScreen(home = ready(section(LibrarySection.WATCHING, listOf(watching, caughtUp))))

        composeRule.onAllNodesWithText("More soon").assertCountEquals(1)
        composeRule.onNodeWithText("More soon").assertIsDisplayed()
        composeRule.onNodeWithText("S1 soon").assertDoesNotExist()
    }

    @Test
    fun `a cached caught up poster still knows the next season`() {
        // The Room cache keeps the countdown but not the air date itself.
        val cached =
            ENTRY.copy(
                id = "c1",
                section = LibrarySection.CAUGHT_UP,
                media =
                    ENTRY.media.copy(
                        id = "mc1",
                        nextEpisodeSeason = 3,
                        nextEpisodeDate = null,
                        daysUntilNextEpisode = 40,
                    ),
            )
        setScreen(home = ready(section(LibrarySection.WATCHING, listOf(cached))))

        composeRule.onNodeWithText("S3 soon").assertIsDisplayed()
    }

    @Test
    fun `single status filter shows the grid without section headers`() {
        setScreen(
            filter = LibraryFilter(status = UserMediaStatus.PLANNED),
            entries = entries("p", 3, section = LibrarySection.PLANNED),
        )

        composeRule.onNodeWithText("Show p1").assertIsDisplayed()
        composeRule.onNodeWithText("Show p3").assertIsDisplayed()
        composeRule.onAllNodesWithTag(SECTION_HEADER_TAG).assertCountEquals(0)
    }

    @Test
    fun `tapping a poster opens the show`() {
        var opened: LibraryEntry? = null
        val shows = entries("w", 2)
        setScreen(home = ready(section(LibrarySection.WATCHING, shows)), onEntryClick = { opened = it })

        composeRule.onNodeWithText("Show w2").performClick()

        assertEquals(shows[1], opened)
    }

    @Test
    fun `stale home shows the saved data banner`() {
        setScreen(
            home = HomeUiState.Ready(listOf(section(LibrarySection.WATCHING, entries("w", 1))), isStale = true),
        )

        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `an empty library shows the empty state`() {
        setScreen(home = ready())

        composeRule.onNodeWithText("Nothing in your library yet").assertIsDisplayed()
    }

    @Test
    fun `a failed home load offers a retry`() {
        var retried = false
        setScreen(home = HomeUiState.Error(IllegalStateException("offline")), onRetry = { retried = true })

        composeRule.onNodeWithText("Retry").performClick()

        assertTrue(retried)
    }

    @Suppress("LongParameterList")
    private fun setScreen(
        filter: LibraryFilter = LibraryFilter(),
        stats: LibraryStats? = null,
        home: HomeUiState = ready(),
        entries: List<LibraryEntry> = emptyList(),
        airingSoon: List<LibraryEntry> = emptyList(),
        onStatusSelected: (UserMediaStatus?) -> Unit = {},
        onToggleSection: (LibrarySection) -> Unit = {},
        onRetry: () -> Unit = {},
        onEntryClick: (LibraryEntry) -> Unit = {},
    ) {
        composeRule.setContent {
            LibraryScreen(
                home = home,
                state = LibraryUiState.Success(entries = entries, loadingMore = false),
                filter = filter,
                stats = stats,
                airingSoon = airingSoon,
                onStatusSelected = onStatusSelected,
                onSortSelected = {},
                onToggleSection = onToggleSection,
                onLoadMore = {},
                onRetry = onRetry,
                onEntryClick = onEntryClick,
                onSearchClick = {},
            )
        }
    }

    private fun ready(vararg sections: HomeSection) = HomeUiState.Ready(sections = sections.toList(), isStale = false)

    private fun section(
        section: LibrarySection,
        entries: List<LibraryEntry>,
        count: Int? = entries.size,
        expanded: Boolean = false,
    ) = HomeSection(
        section = section,
        entries = entries,
        count = count,
        expanded = expanded,
        nextCursor = null,
        loadingMore = false,
    )

    /** [count] shows titled "Show {prefix}1"…, each with its own entry and media id. */
    private fun entries(
        prefix: String,
        count: Int,
        section: LibrarySection = LibrarySection.WATCHING,
    ): List<LibraryEntry> =
        (1..count).map { n ->
            ENTRY.copy(
                id = "$prefix$n",
                section = section,
                media = ENTRY.media.copy(id = "m-$prefix$n", title = "Show $prefix$n"),
            )
        }

    private companion object {
        val STATS =
            LibraryStats(
                total = 9,
                byStatus = mapOf(UserMediaStatus.WATCHING to 3, UserMediaStatus.PLANNED to 6),
                averageScore = null,
                ratedCount = 0,
                episodesWatched = 0,
                topGenres = emptyList(),
                addedThisMonth = 0,
                favorites = 0,
            )
        val ENTRY =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.WATCHING,
                score = BigDecimal("8.5"),
                progress = 3,
                favorite = false,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media =
                    Media(
                        id = "media-1",
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
