package com.anarky.showtrack.feature.library

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
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
 * `:feature:profile`'s `PushNotifierTest` uses, and the same reasoning `:core:designsystem`'s
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
                state = LibraryUiState.Success(entries = emptyList(), loadingMore = false),
                filter = LibraryFilter(),
                stats = null,
                airingSoon = emptyList(),
                onStatusSelected = {},
                onSortSelected = {},
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
        setScreen(entries = listOf(ENTRY), airingSoon = listOf(upcoming))
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
    fun `a row says which episode is next and when`() {
        setScreen(entries = listOf(ENTRY))

        composeRule.onNodeWithText("Watching · Ep 1100 in 4 days").assertIsDisplayed()
    }

    @Suppress("LongParameterList")
    private fun setScreen(
        filter: LibraryFilter = LibraryFilter(),
        stats: LibraryStats? = null,
        entries: List<LibraryEntry> = emptyList(),
        airingSoon: List<LibraryEntry> = emptyList(),
        onStatusSelected: (UserMediaStatus?) -> Unit = {},
    ) {
        composeRule.setContent {
            LibraryScreen(
                state = LibraryUiState.Success(entries = entries, loadingMore = false),
                filter = filter,
                stats = stats,
                airingSoon = airingSoon,
                onStatusSelected = onStatusSelected,
                onSortSelected = {},
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSearchClick = {},
            )
        }
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
