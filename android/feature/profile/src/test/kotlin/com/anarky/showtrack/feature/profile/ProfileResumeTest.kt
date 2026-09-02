package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The user-path half of round 1's split (review finding, round 2): before [ProfileViewModel.refreshStats]
 * was pulled out of [ProfileViewModel.refresh], `init` also fetched stats, so `ProfileScreen`'s
 * `LifecycleResumeEffect` was a redundant re-fetcher — a ViewModel-only test could not tell whether
 * the effect was actually wired to anything, and it didn't matter, because `init` covered the gap.
 * Splitting the two made the effect load-bearing: it is now the ONLY production caller of
 * [ProfileViewModel.refreshStats] for the initial load AND for a resume, and
 * `StatsSection`'s retry button is the only caller for a manual retry. `ProfileViewModelTest`'s
 * `init does not fetch stats`/`toggling push does not fetch stats` prove the NEGATIVE (`init` and
 * push toggles do NOT call it); this test proves the POSITIVE — that something real actually does,
 * on all three of the paths a user can reach it from. Modelled directly on `FavoritesResumeTest`
 * (task 9b.4, round 1), including its own reasoning for why this needs no Hilt harness.
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not `createComposeRule()`: only the explicit
 * form exposes `.activityRule.scenario`, which is what lets this test drive the Activity through
 * CREATED -> RESUMED via [androidx.test.core.app.ActivityScenario.moveToState] — the same mechanism
 * Android itself uses under a real "returned from another screen" navigation, rather than reaching
 * into [ProfileViewModel] directly.
 *
 * No Hilt harness needed: [ProfileScreen]'s stateful overload takes `viewModel` as an ordinary
 * parameter with a `hiltViewModel()` DEFAULT — passing one explicitly here (a plain
 * [ProfileViewModel] built against [FakeLibraryRepository]) never evaluates that default, so a bare
 * `ComponentActivity` is enough.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `stats are fetched on first composition, re-fetched on resume, and re-fetched again on retry`() {
        // Stays failing throughout: the point is to count CALLS, not to reach Success, and an
        // ErrorState's retry button is the one interaction this test needs to click. A gate is not
        // needed here the way `FakeLibraryRepository.statsGate` is in `ProfileViewModelTest` —
        // nothing in this test needs to observe a state mid-flight.
        val failure = IOException("stats offline")
        val repository = FakeLibraryRepository(statsFailure = failure)
        val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), repository)

        composeRule.setContent {
            ProfileScreen(onSignedOut = {}, onImportClick = {}, viewModel = viewModel)
        }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.profile_stats_error)).assertIsDisplayed()
        // The call `createAndroidComposeRule` launching its Activity straight to RESUMED produces:
        // `Lifecycle` replays ON_CREATE/ON_START/ON_RESUME to a freshly-registered observer, which
        // is what fires `LifecycleResumeEffect` on the very first composition, with no `init`
        // fetch left to land on top of it any more (round 1).
        assertEquals(1, repository.statsCalls)

        // A REAL pause/resume cycle, the shape Android dispatches for an actual
        // Profile -> some other screen -> Back trip.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertEquals(2, repository.statsCalls)

        // The retry button inside StatsSection's ErrorState — StatsSection's `onRetry` must be
        // wired to `refreshStats`, not `refresh` (round 0/round 1's shipped bug: retrying used to
        // re-run only the push read, leaving the error banner's own retry a dead button).
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()
        composeRule.waitForIdle()

        assertEquals(3, repository.statsCalls)
    }
}
