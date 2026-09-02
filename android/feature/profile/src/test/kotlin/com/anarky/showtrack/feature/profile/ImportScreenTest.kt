package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * Drives the `internal` stateless [ImportScreen] overload directly — `AuthScreen`/
 * `FavoritesScreen`'s pattern — so no `ViewModel` and no Hilt graph is needed. `createComposeRule`,
 * not `createAndroidComposeRule`: no Activity is needed.
 *
 * The behaviours pinned here are rendering decisions no [ImportViewModelTest] can see (note 6 of
 * this task's brief, and `FavoritesScreenTest`'s own KDoc for the general point): which STRING
 * renders for a given [ImportUiState], and — the gap the brief calls out by name — that a
 * [ImportSummary.truncated] import is shown DIFFERENTLY from a complete one. A
 * [ImportUiState.Success] carrying `truncated = true` maps identically onto [ImportViewModel]'s
 * own state either way; only a composed screen can pin whether the extra notice actually renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Task brief step 4: both limitations must be said BEFORE the user types a username, not only
     * surfaced in an error afterward. Pinned against a [ImportUiState.Form] with no error and an
     * empty field — the state the screen opens in.
     */
    @Test
    fun `the public-profile and one-way limitations are shown before typing anything`() {
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Form(), onImport = {}, onFinished = {})
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.import_public_requirement)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.import_one_way_notice)).assertIsDisplayed()
    }

    @Test
    fun `typing a username and tapping import invokes onImport with it`() {
        var imported: String? = null
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Form(), onImport = { imported = it }, onFinished = {})
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.import_username_label))
            .performTextInput("someone")
        composeRule.onNodeWithText(context.getString(R.string.import_submit)).performClick()

        assertTrue(imported == "someone")
    }

    @Test
    fun `tapping skip invokes onFinished`() {
        var finished = false
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Form(), onImport = {}, onFinished = { finished = true })
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.import_skip)).performClick()

        assertTrue(finished)
    }

    /**
     * Round 3, task 9b.6 fix round: a rapid double-tap of Skip fires `onFinished` twice before the
     * NavHost recomposes away from this screen — the back stack mutation from the FIRST call is
     * synchronous, but this composable has no way to know that happened, so the button stays
     * clickable and a second tap used to call `onFinished()` again. `routeShowTrackNavigation`'s
     * `LibraryRoute` branch then saw the SECOND call with `currentDestination` already moved on to
     * `ProfileRoute` (from the first call's pop), missed its own pop condition, and pushed a
     * second `LibraryRoute` — `[Library, Profile, Library]`, the exact duplicate-library shape M1
     * existed to remove. Pinned here at the UI layer, where the fix actually lives, rather than at
     * `routeShowTrackNavigation`, whose own decision logic is unchanged by this fix — see
     * `ShowTrackGraphRoutingTest`'s `` `routing to LibraryRoute from Profile with Library already
     * underneath is still an ordinary push` `` for why that logic staying unchanged matters.
     */
    @Test
    fun `tapping skip twice only invokes onFinished once`() {
        var finishedCalls = 0
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Form(), onImport = {}, onFinished = { finishedCalls++ })
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val skip = composeRule.onNodeWithText(context.getString(R.string.import_skip))
        skip.performClick()
        skip.performClick()

        assertEquals(1, finishedCalls)
    }

    /**
     * The one case the task brief names by name (correction 3): copy must cover both "no such
     * user" and "profile is private" honestly, never picking one — pinned by asserting the actual
     * neutral copy renders for [ImportError.ProfileNotPublic], not a guessed cause.
     */
    @Test
    fun `a ProfileNotPublic error shows the neutral copy, not a guessed cause`() {
        composeRule.setContent {
            ImportScreen(
                state = ImportUiState.Form(error = ImportError.ProfileNotPublic),
                onImport = {},
                onFinished = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.import_error_not_public))
            .assertIsDisplayed()
    }

    @Test
    fun `a successful import shows the three counts`() {
        val summary = ImportSummary(imported = 12, skipped = 3, failed = 1, truncated = false)
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Success(summary), onImport = {}, onFinished = {})
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.import_result_imported, 12, 12))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.import_result_skipped, 3, 3))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.import_result_failed, 1, 1))
            .assertIsDisplayed()
    }

    /**
     * The regression this test exists to catch: `ImportResult`'s `if (summary.truncated)` deleted
     * (or its condition inverted) leaves every OTHER assertion in this file green — none of them
     * carry `truncated = true` — so this is the one place a mutant on that branch is actually
     * caught. `substring = false` (the `onNodeWithText` default) matters here the same way the
     * task brief's own `"0.0"` example warns about: the notice text must be its own node, not a
     * substring assumed to appear inside a longer one.
     */
    @Test
    fun `a truncated import shows the truncated notice, a complete one does not`() {
        val truncated = ImportSummary(imported = 500, skipped = 0, failed = 0, truncated = true)
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Success(truncated), onImport = {}, onFinished = {})
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.import_truncated_notice))
            .assertIsDisplayed()
    }

    @Test
    fun `a complete import shows no truncated notice`() {
        val complete = ImportSummary(imported = 40, skipped = 2, failed = 0, truncated = false)
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Success(complete), onImport = {}, onFinished = {})
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.import_truncated_notice))
            .assertDoesNotExist()
    }

    @Test
    fun `tapping done on a successful import invokes onFinished`() {
        var finished = false
        val summary = ImportSummary(imported = 1, skipped = 0, failed = 0, truncated = false)
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Success(summary), onImport = {}, onFinished = { finished = true })
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.import_done)).performClick()

        assertTrue(finished)
    }

    /** The mirror of `` `tapping skip twice only invokes onFinished once` `` for the Done button. */
    @Test
    fun `tapping done twice only invokes onFinished once`() {
        var finishedCalls = 0
        val summary = ImportSummary(imported = 1, skipped = 0, failed = 0, truncated = false)
        composeRule.setContent {
            ImportScreen(state = ImportUiState.Success(summary), onImport = {}, onFinished = { finishedCalls++ })
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val done = composeRule.onNodeWithText(context.getString(R.string.import_done))
        done.performClick()
        done.performClick()

        assertEquals(1, finishedCalls)
    }

    /**
     * M3, task 9b.6 fix round: nothing rendered [ImportUiState.Form.submitting] `= true` before
     * this test, and `import_submitting` was unreferenced by any test — the `enabled =
     * !state.submitting` guard on the primary button was correct in the production code but
     * unpinned, which is exactly the gap that would let a user tap Import twice during the
     * multi-second synchronous call and race two full AniList imports against each other
     * server-side.
     */
    @Test
    fun `while submitting, the button shows the submitting label and is disabled`() {
        // A mutable state DRIVEN FROM THE TEST, not a fixed `state = Form(submitting = true)`:
        // that field is ALSO disabled while submitting, so a username could never be typed into
        // it directly under a submitting state, and `enabled = !state.submitting &&
        // username.isNotBlank()` has TWO reasons to disable the button — a blank field alone
        // would mask a deleted `!state.submitting` guard. Typing while `submitting = false`, THEN
        // flipping to `true`, isolates the submitting half specifically; `username` is the
        // stateless overload's own `remember`ed draft and survives the state flip within one
        // composition.
        var state by mutableStateOf<ImportUiState>(ImportUiState.Form())
        composeRule.setContent {
            ImportScreen(state = state, onImport = {}, onFinished = {})
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.import_username_label))
            .performTextInput("someone")

        state = ImportUiState.Form(submitting = true)
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.import_submitting))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithText(context.getString(R.string.import_submit))
            .assertDoesNotExist()
    }

    /**
     * M5, task 9b.6 fix round: `ErrorState`'s retry action had no `isNotBlank()` guard, unlike the
     * primary button right below it — fail once, clear the username field, tap Retry, and the
     * unguarded version would call `onImport("")`, a guaranteed 422 the user never chose to send.
     */
    @Test
    fun `retrying a failed import with a blank username does nothing`() {
        var imported: String? = null
        composeRule.setContent {
            ImportScreen(
                state = ImportUiState.Form(error = ImportError.Unknown),
                onImport = { imported = it },
                onFinished = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(imported == null)
    }
}
