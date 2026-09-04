package com.anarky.showtrack.feature.detail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The seam `DetailScreenTest` cannot see (fix round 1, BLOCKING B1): that suite drives only the
 * `internal` STATELESS [DetailScreen] overload, so nothing exercised the stateful
 * `DetailScreen(activeGroup, viewModel)` — the `LifecycleResumeEffect`/`collectAsStateWithLifecycle`
 * wiring that makes [DetailViewModel.setActiveGroup]/[DetailViewModel.proposeToGroup] REACHABLE at
 * all. Three realistic mutations of `DetailScreen.kt:81-102` (dropping `currentGroupId` from the
 * effect's key, calling `setActiveGroup(null)` unconditionally, and wiring the propose button to a
 * no-op) all compiled and left the whole suite green — `FeedEntryHiltTest`'s identical finding one
 * task ago, for the identical `StateFlow<ActiveGroupState>` → `LifecycleResumeEffect` → ViewModel
 * shape.
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not `createComposeRule()`, and no Hilt harness:
 * [DetailScreen]'s `viewModel` parameter has a `hiltViewModel()` DEFAULT that is never evaluated
 * once a real [DetailViewModel] is passed explicitly — `FavoritesResumeTest`'s exact technique,
 * verbatim, extended from a `LibraryRepository`-only ViewModel to one that also takes a
 * [GroupRepository][com.anarky.showtrack.core.data.repository.GroupRepository]. No new Gradle
 * dependency: `androidx.compose.ui:ui-test-junit4` (already a test dependency here) carries
 * `androidx.activity:activity-compose` transitively.
 */
@RunWith(RobolectricTestRunner::class)
class DetailResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `changing the activeGroup flow re-scopes the group section fetch`() {
        val groups = FakeGroupRepository()
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = "group-a"),
            )

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()
        assertEquals(listOf("group-a" to "media-1"), groups.progressCalls)

        // The active group changes underneath an ALREADY-COMPOSED Detail screen — a switch made
        // from Feed or the group switcher while this screen sits on the back stack, the exact
        // event §3.5 says must re-scope the section rather than leave it showing the old group's
        // rows.
        activeGroup.value = ActiveGroupState.Success(groups = listOf(BETA), activeGroupId = "group-b")
        composeRule.waitForIdle()
        assertEquals(listOf("group-a" to "media-1", "group-b" to "media-1"), groups.progressCalls)
    }

    @Test
    fun `the propose button on the real, composed screen actually proposes`() {
        val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = "group-a"),
            )

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_group_propose_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, groups.proposeCalls)
        assertEquals("group-a", groups.lastProposeGroupId)
        assertEquals("media-1", groups.lastProposeMediaId)
    }

    private fun detailViewModel(groups: FakeGroupRepository): DetailViewModel =
        DetailViewModel(
            SavedStateHandle(mapOf("mediaId" to "media-1")),
            DetailViewModelTest.FakeMedia(),
            DetailViewModelTest.FakeLibrary(entry = null),
            groups,
        )

    private companion object {
        val ALPHA = Group(id = "group-a", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA = Group(id = "group-b", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))

        val WATCHLIST_ENTRY =
            WatchlistEntry(
                id = "watchlist-1",
                media =
                    MediaSummary(
                        source = MediaSource.ANILIST,
                        externalId = "21",
                        type = MediaType.ANIME,
                        title = "One Piece",
                        year = 1999,
                        genres = listOf("Action"),
                        coverImageUrl = null,
                    ),
                mediaId = "media-1",
                proposedBy = "user-1",
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
    }
}
