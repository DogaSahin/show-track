package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ImportSummary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
