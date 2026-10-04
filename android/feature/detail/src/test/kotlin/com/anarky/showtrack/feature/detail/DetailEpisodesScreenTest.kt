package com.anarky.showtrack.feature.detail

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Season
import com.anarky.showtrack.feature.detail.DetailViewModelTest.Companion.ENTRY
import com.anarky.showtrack.feature.detail.DetailViewModelTest.Companion.MEDIA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
// Tall, so every row of the screen's lazy list is composed and findable.
@Config(qualifiers = "w411dp-h3000dp")
class DetailEpisodesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setScreen(
        episodes: EpisodesState,
        entry: LibraryEntry? = ENTRY,
        actions: DetailActions = DetailActions(),
    ) {
        composeRule.setContent {
            DetailScreen(
                state =
                    DetailUiState.Success(
                        data =
                            DetailData(
                                media = MEDIA.copy(type = MediaType.TV, source = MediaSource.TMDB),
                                entry = entry,
                            ),
                        episodes = episodes,
                    ),
                groups = emptyList(),
                actions = actions,
                today = TODAY,
            )
        }
    }

    @Test
    fun `an episode row reads as one sentence and a tap toggles it`() {
        val toggled = mutableListOf<Episode>()
        setScreen(
            episodes = ready(watched = setOf("e1")),
            actions = DetailActions(episodes = EpisodeActions(onToggleEpisode = { toggled += it })),
        )

        // "Pilot, aired 28 Sep, watched"; the date's spelling is the locale's, so match around it.
        composeRule
            .onNode(
                hasContentDescription("Pilot, aired", substring = true) and
                    hasContentDescription(", watched", substring = true),
            ).assertHasClickAction()
            .performClick()

        assertEquals(listOf(AIRED), toggled)
    }

    @Test
    fun `an episode not aired yet is not tappable and says when it airs`() {
        setScreen(episodes = ready())

        composeRule
            .onNodeWithText(
                context.getString(R.string.detail_episode_airs_tomorrow),
                useUnmergedTree = true,
            ).assertExists()
        composeRule.onNodeWithContentDescription("Episode 2", substring = true).assertIsNotEnabled()
    }

    @Test
    fun `not in the library shows the episodes without watched state`() {
        setScreen(episodes = ready(tracking = false), entry = null)

        composeRule.onNodeWithContentDescription("Pilot, aired", substring = true).assertExists()
        composeRule.onNode(hasContentDescription("watched", substring = true)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.detail_add_button)).assertExists()
    }

    @Test
    fun `a list not fetched yet says so`() {
        setScreen(episodes = EpisodesState.NotAvailable)

        composeRule.onNodeWithText(context.getString(R.string.detail_episodes_not_loaded)).assertExists()
    }

    @Test
    fun `removing asks first and only removes on confirm`() {
        var removes = 0
        setScreen(episodes = ready(), actions = DetailActions(onRemoveFromLibrary = { removes++ }))

        composeRule.onNodeWithContentDescription(context.getString(R.string.detail_more)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_remove_from_library)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_remove_cancel)).performClick()
        assertEquals(0, removes)

        composeRule.onNodeWithContentDescription(context.getString(R.string.detail_more)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_remove_from_library)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_remove_confirm)).performClick()
        assertEquals(1, removes)
    }

    @Test
    fun `the heart says whether it is on`() {
        setScreen(episodes = ready())

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.detail_favorite_content_description))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.detail_favorite_off),
                ),
            )
    }

    @Test
    fun `a season header says whether it is open and toggles it`() {
        val toggled = mutableListOf<Int>()
        setScreen(
            episodes = ready(),
            actions = DetailActions(episodes = EpisodeActions(onToggleSeason = { toggled += it })),
        )

        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.detail_season_expanded),
                ),
            ).performClick()

        assertEquals(listOf(1), toggled)
    }

    @Test
    fun `a long anime opens on the range holding the next episode`() {
        val episodes = (1..150).map { n -> Episode(id = "a$n", number = n, title = null, airDate = null, aired = true) }
        composeRule.setContent {
            DetailScreen(
                state =
                    DetailUiState.Success(
                        data = DetailData(media = MEDIA, entry = ENTRY),
                        episodes =
                            EpisodesState.Ready(
                                list =
                                    EpisodeList(
                                        syncedAt = Instant.EPOCH,
                                        totalEpisodes = 150,
                                        seasons = listOf(Season(1, episodes)),
                                    ),
                                watched = (1..120).map { "a$it" }.toSet(),
                                tracking = true,
                                expanded = setOf(1),
                            ),
                    ),
                groups = emptyList(),
                actions = DetailActions(),
                today = TODAY,
            )
        }

        composeRule.onNodeWithText("101–150").assertExists()
        // Episode 121 is next, so the second range is open.
        composeRule.onNodeWithContentDescription("Episode 121", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Episode 5,", substring = true).assertDoesNotExist()
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 4)
        val AIRED = Episode(id = "e1", number = 1, title = "Pilot", airDate = LocalDate.of(2026, 9, 28), aired = true)
        val UPCOMING = Episode(id = "e2", number = 2, title = null, airDate = LocalDate.of(2026, 10, 5), aired = false)

        fun ready(
            watched: Set<String> = emptySet(),
            tracking: Boolean = true,
        ) = EpisodesState.Ready(
            list =
                EpisodeList(
                    syncedAt = Instant.parse("2026-10-01T08:00:00Z"),
                    totalEpisodes = 2,
                    seasons = listOf(Season(number = 1, episodes = listOf(AIRED, UPCOMING))),
                ),
            watched = watched,
            tracking = tracking,
            expanded = setOf(1),
        )
    }
}
