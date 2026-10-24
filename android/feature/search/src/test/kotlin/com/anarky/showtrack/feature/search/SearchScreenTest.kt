package com.anarky.showtrack.feature.search

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a row reads as one sentence and its Add button speaks for itself`() {
        setResults(SearchResult(BLUE_LOCK))

        composeRule.onNodeWithContentDescription("Blue Lock, Anime, 2022, Sports, Drama").assertExists()
        composeRule
            .onNodeWithContentDescription("Add Blue Lock to your library")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasClickAction())
    }

    @Test
    fun `a title already in the library shows its status instead of Add`() {
        setResults(SearchResult(BLUE_LOCK, mediaId = "m-1", libraryStatus = UserMediaStatus.PLANNED))

        composeRule.onNodeWithContentDescription("In your library, Planned").assertExists()
        composeRule.onNodeWithContentDescription("Add Blue Lock to your library").assertDoesNotExist()
    }

    @Test
    fun `tapping the row opens it and only Add adds`() {
        val opened = mutableListOf<SearchResult>()
        val added = mutableListOf<SearchResult>()
        val result = SearchResult(BLUE_LOCK)
        setResults(result, SearchActions(onOpen = { opened += it }, onAdd = { added += it }))

        composeRule.onNodeWithContentDescription("Blue Lock, Anime, 2022, Sports, Drama").performClick()
        assertEquals(listOf(result), opened)
        assertEquals(emptyList<SearchResult>(), added)

        composeRule.onNodeWithContentDescription("Add Blue Lock to your library").performClick()
        assertEquals(listOf(result), added)
        assertEquals(1, opened.size)
    }

    @Test
    fun `recent searches run, fill the field, or clear`() {
        val ran = mutableListOf<String>()
        val filled = mutableListOf<String>()
        var cleared = false
        composeRule.setContent {
            SearchScreen(
                state = SearchUiState.Idle,
                query = "",
                recent = listOf("frieren", "bebop"),
                actions =
                    SearchActions(
                        onRunRecent = { ran += it },
                        onFillRecent = { filled += it },
                        onClearRecent = { cleared = true },
                    ),
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.search_recent_heading)).assertExists()
        composeRule.onNodeWithText("bebop").performClick()
        composeRule.onNodeWithContentDescription("Edit frieren").performClick()
        composeRule.onNodeWithText(context.getString(R.string.search_recent_clear)).performClick()

        assertEquals(listOf("bebop"), ran)
        assertEquals(listOf("frieren"), filled)
        assertEquals(true, cleared)
    }

    @Test
    fun `with no recent searches the area stays empty`() {
        composeRule.setContent {
            SearchScreen(state = SearchUiState.Idle, query = "", recent = emptyList(), actions = SearchActions())
        }

        composeRule.onNodeWithText(context.getString(R.string.search_recent_heading)).assertDoesNotExist()
    }

    private fun setResults(
        result: SearchResult,
        actions: SearchActions = SearchActions(),
    ) {
        composeRule.setContent {
            SearchScreen(
                state =
                    SearchUiState.Success(
                        results = SearchResults(items = listOf(result), hasMore = false, degraded = emptyList()),
                    ),
                query = "blue lock",
                recent = emptyList(),
                actions = actions,
            )
        }
    }

    private companion object {
        val BLUE_LOCK =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "137822",
                type = MediaType.ANIME,
                title = "Blue Lock",
                year = 2022,
                genres = listOf("Sports", "Drama", "Action"),
                coverImageUrl = null,
            )
    }
}
