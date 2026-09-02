package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * Task 9b.5, round 1 — the regression guard `ProfileViewModelTest` cannot provide. That suite can
 * only pin that [ProfileViewModel.statsState] carries a given [LibraryStats] value; the two
 * behaviours the brief actually asks for — "an unrated library shows no average rather than zero"
 * and "the average is labelled with what it is an average of" — are rendering decisions made
 * entirely inside `StatsContent`, which no ViewModel test reaches. A mutant that coerces a null
 * average to `"0.0"` at render time, or that renders an absent status as `"Completed: 0"`, leaves
 * every field [ProfileViewModel] holds untouched — a `ProfileViewModelTest` literally cannot see
 * it, because the ViewModel never held the wrong value in the first place. Only a composed screen
 * can pin which STRING renders (`FavoritesScreenTest`'s own KDoc, task 9b.4).
 *
 * Drives the `internal` stateless [ProfileScreen] overload directly — `LibraryScreen`/
 * `FavoritesScreen`'s pattern — so no `ViewModel` and no Hilt graph is needed. `createComposeRule`,
 * not `createAndroidComposeRule`: no Activity is needed. Robolectric supplies the Android runtime
 * `stringResource`/`pluralStringResource` need; `sdk = 35` is pinned per-class here, matching this
 * module's existing `PushNotifierTest`/`PushRegistrarTest`/`ProfileViewModelTest` convention
 * rather than introducing a module-wide `robolectric.properties` this task did not ask for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The exact statement the brief forbids: replace `StatsContent`'s null-average branch with
     * `average?.toPlainString() ?: "0.0"` and this renders "Average score: 0.0 across 0 rated
     * titles" — "you rate everything zero" — instead of "No ratings yet". The first assertion
     * below is what actually catches that mutant (the "No ratings yet" node stops existing); the
     * second is a belt-and-braces check for the same mutant with `substring = true` (review
     * finding, round 2 — `onNodeWithText` defaults to an EXACT match, so a bare
     * `onNodeWithText("0.0")` would only ever match a node whose entire text was "0.0" and would
     * sail straight past "Average score: 0.0 across 0 rated titles").
     */
    @Test
    fun `an unrated library shows no average rather than zero`() {
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(UNRATED_STATS),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.profile_stats_no_ratings))
            .assertIsDisplayed()
        composeRule.onNodeWithText("0.0", substring = true).assertDoesNotExist()
    }

    /**
     * `rated_count` travels with `average_score` precisely so the screen can say "8.4 across 12
     * rated titles" — an average over 12 of 400 is not "your average score". Pinned against the
     * real `<plurals>` resource (`getQuantityString`), not a hand-typed literal, so a mutant that
     * drops `ratedCount` from the call or swaps which argument fills which placeholder fails here.
     */
    @Test
    fun `the average is labelled with what it is an average of`() {
        val stats = LibraryStats(total = 400, byStatus = emptyMap(), averageScore = BigDecimal("8.4"), ratedCount = 12)
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(stats),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = context.resources.getQuantityString(R.plurals.profile_stats_average, 12, "8.4", 12)

        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    /**
     * The other regression the brief names: replace `StatsContent`'s `?: return@forEach` with
     * `?: 0` and every status absent from `by_status` renders as `"<Label>: 0"` instead of not
     * rendering at all — the server sends what exists (`LibraryStats`'s own KDoc), and the client
     * must not invent the rest. [PARTIAL_STATS] carries WATCHING only, so every other status row
     * must be entirely absent.
     */
    @Test
    fun `an absent status renders as absent, not as zero`() {
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(PARTIAL_STATS),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.profile_stats_status_row, "Watching", 5))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.profile_stats_status_row, "Completed", 0))
            .assertDoesNotExist()
    }

    private companion object {
        val UNRATED_STATS =
            LibraryStats(
                total = 5,
                byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                averageScore = null,
                ratedCount = 0,
            )

        val PARTIAL_STATS = UNRATED_STATS
    }
}
