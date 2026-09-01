package com.anarky.showtrack.feature.library

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryFilter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
