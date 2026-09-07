package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertTrue
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
 * `stringResource`/`pluralStringResource` need; `sdk = 35` comes from this module's
 * `src/test/resources/robolectric.properties` (whole-branch review fix round — this class and its
 * five siblings previously each carried their own `@Config(sdk = [35])`, the one thing in this
 * module that left the NEXT Robolectric test class silently defaulting to `targetSdk` and failing
 * unexplained), matching every other Robolectric-using module in this repo except `:app`.
 */
@RunWith(RobolectricTestRunner::class)
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
                onGroupsClick = {},
                onImportClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // `performScrollTo()` rather than a bigger `@Config(qualifiers = ...)`: the stats block
        // grew a headline figure, a distribution bar and a legend, so the caption under the average
        // now sits below Robolectric's default 470px root. A qualifier fixes that until the next
        // section is added; scrolling is what a reader does on a screen that scrolls, and does not
        // rot as the content grows.
        composeRule
            .onNodeWithText(context.getString(R.string.profile_stats_no_ratings))
            .performScrollTo()
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
        val stats =
            LibraryStats(
                total = 400,
                byStatus = emptyMap(),
                averageScore = BigDecimal("8.4"),
                ratedCount = 12,
                episodesWatched = 1200,
                topGenres = emptyList(),
                addedThisMonth = 9,
                favorites = 30,
            )
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(stats),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
                onGroupsClick = {},
                onImportClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // The figure and its caption are two nodes now, not one sentence, so both are asserted:
        // the number alone would still pass if the screen stopped saying what it averages over,
        // which is the exact regression this test exists for.
        val caption = context.resources.getQuantityString(R.plurals.profile_stats_average_label, 12, 12)

        composeRule.onNodeWithText("8.4").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(caption).performScrollTo().assertIsDisplayed()
    }

    /**
     * The other regression the brief names, carried over to the distribution bar: the server sends
     * what exists (`LibraryStats`'s own KDoc), and the client must not invent the rest.
     * [PARTIAL_STATS] carries WATCHING only, so every other status must be absent from the legend
     * entirely rather than present at zero.
     *
     * The bar itself makes this stricter than it was for the old text rows, not weaker: a segment
     * is sized by `Modifier.weight(count)`, and a zero weight is not allowed — so a `?: 0` that
     * invented the missing four statuses would not merely render four wrong labels, it would fail
     * at layout. This assertion covers the legend, which is where an invented status would be
     * visible.
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
                onGroupsClick = {},
                onImportClick = {},
            )
        }

        composeRule.onNodeWithText("Watching").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertDoesNotExist()
        composeRule.onNodeWithText("Planned").assertDoesNotExist()
    }

    /**
     * M2, task 9b.6 fix round: `ImportSection`'s `Button(onClick = onImportClick)` had no test
     * anywhere clicking it — every existing call site passed `onImportClick = {}` and never tapped
     * the button, so `onClick = {}` (Profile's door to `ImportRoute` silently dead) would have left
     * this entire suite green. Drives the stateless overload directly, no Hilt or ViewModel needed.
     *
     * `@Config(qualifiers = ...)` widens the Robolectric virtual display for this one test, and
     * this is a test-environment fact worth recording rather than a stylistic pick:
     * `createComposeRule()`'s default Robolectric root measured a fixed 320x470px in this project
     * — NOT auto-sized to content — and `ProfileScreen`'s full stack (push card, stats card, then
     * `ImportSection`) genuinely exceeds that height. Confirmed by printing the semantics tree, not
     * guessed: the button's own node reported `Actions = […, OnClick, …]` — present, genuinely
     * clickable, and unambiguously matched (`onNodeWithText` found exactly one node) — with
     * `t=454.0, b=454.0`, collapsed to zero height by the 470px floor; Compose's own hit-testing
     * cannot route a synthetic tap to a zero-area node, so `performClick()` silently found nothing
     * to click rather than throwing. A Pixel-sized qualifier gives this test enough visible height
     * that the button lands within it without needing to scroll first — `ProfileScreen`'s `Column`
     * gained a `.verticalScroll` in round 3 below (this test predates it), so a real device
     * shorter than this qualifier now genuinely scrolls to reach the button rather than clipping
     * it away entirely; `` `sign-out is reachable by scrolling…` `` below is what tests that path
     * directly, by constraining the viewport on purpose rather than widening it.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping the import action invokes onImportClick`() {
        var clicked = false
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(UNRATED_STATS),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
                onGroupsClick = {},
                onImportClick = { clicked = true },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // Round 2 (task 9b.6 fix round, minor): asserted displayed BEFORE clicking. A zero-height
        // node collapsed by Robolectric's virtual window (the exact trap this test's own KDoc
        // documents diagnosing) and a genuinely dead `onClick` produce IDENTICAL failures from
        // `assertTrue(clicked)` alone — this line is what tells a future reader which one they are
        // looking at if a later section change pushes the button off-window again.
        composeRule
            .onNodeWithText(context.getString(R.string.profile_import_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertTrue(clicked)
    }

    /**
     * Round 3, task 9b.6 fix round: round 2's own disclosure said there was "no lightweight way"
     * to test `ProfileScreen`'s `.verticalScroll` under Robolectric. There is — this test, using
     * the same trick `` `tapping the import action invokes onImportClick` `` diagnosed by accident:
     * a Robolectric root does not auto-size to content, so `Modifier.heightIn(max = 40.dp)` here
     * constrains the SAME `Column` to a genuinely too-short viewport on purpose, and
     * `performScrollTo()` is what a real finger drag does. Sign-out is chosen because it is the
     * functional necessity `.verticalScroll` exists to protect (this class's own KDoc) — a scroll
     * that could reach some OTHER row but not this one would still be a real regression.
     */
    @Test
    fun `sign-out is reachable by scrolling when the viewport is too short to show everything at once`() {
        composeRule.setContent {
            ProfileScreen(
                pushState = PushState.NoDistributor,
                statsState = LibraryStatsUiState.Success(UNRATED_STATS),
                signOutError = false,
                onEnablePush = {},
                onDisablePush = {},
                onStatsRetry = {},
                onSignOut = {},
                onGroupsClick = {},
                onImportClick = {},
                modifier = Modifier.heightIn(max = 40.dp),
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.profile_sign_out))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private companion object {
        val UNRATED_STATS =
            LibraryStats(
                total = 5,
                byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                averageScore = null,
                ratedCount = 0,
                episodesWatched = 0,
                topGenres = emptyList(),
                addedThisMonth = 0,
                favorites = 0,
            )

        val PARTIAL_STATS = UNRATED_STATS
    }
}
