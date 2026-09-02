package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private const val NTFY = "io.heckel.ntfy"

/**
 * Robolectric for the same reason `core/data`'s `AuthRepositoryTest` needs it: `signOut()`'s
 * caught-failure path now logs through `android.util.Log`, which a plain JVM test answers with
 * "not mocked" — and THROWS, which would fail `a failed sign-out does not flip signedOut ...` for
 * the opposite of the reason it exists (the throw happens inside the very `catch` block that test
 * is checking, before `mutableSignOutError` is ever set).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the launch inside `signOut` run at all —
    // the push tests below don't need it (enablePush/disablePush/refresh are synchronous), but
    // setting it unconditionally costs nothing and keeps this class' setup uniform.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no installed distributor is reported as NoDistributor`() {
        // The state the whole prompt exists for. A user here receives nothing, forever, and the
        // only conclusion available without the prompt is "push is broken" rather than "push
        // needs one more app" (decision A-A).
        val state = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), FakeLibraryRepository()).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `an installed but unchosen distributor is offered`() {
        val state =
            ProfileViewModel(
                FakeDistributors(installed = listOf(NTFY)),
                FakeAuthRepository(),
                FakeLibraryRepository(),
            ).pushState.value

        assertEquals(PushState.Available(listOf(NTFY)), state)
    }

    @Test
    fun `choosing a distributor moves to Registered`() {
        val viewModel =
            ProfileViewModel(FakeDistributors(installed = listOf(NTFY)), FakeAuthRepository(), FakeLibraryRepository())

        viewModel.enablePush(NTFY)

        assertEquals(PushState.Registered(NTFY), viewModel.pushState.value)
    }

    @Test
    fun `a saved distributor that is no longer installed is not reported as registered`() {
        // The connector keeps the saved choice after the app is uninstalled. Trusting it would
        // show "episode alerts are on" for an app that is gone — the silent failure this state
        // machine exists to prevent, wearing a green tick.
        val state =
            ProfileViewModel(
                FakeDistributors(installed = emptyList(), saved = NTFY),
                FakeAuthRepository(),
                FakeLibraryRepository(),
            ).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `a saved distributor is ignored when a DIFFERENT one is installed`() {
        val state =
            ProfileViewModel(
                FakeDistributors(installed = listOf("org.other.distributor"), saved = NTFY),
                FakeAuthRepository(),
                FakeLibraryRepository(),
            ).pushState.value

        assertEquals(PushState.Available(listOf("org.other.distributor")), state)
    }

    @Test
    fun `turning push off returns to Available rather than NoDistributor`() {
        // The distinction matters to the user: the app is still installed, so the screen must
        // offer to turn it back on rather than tell them to go and install something.
        val distributors = FakeDistributors(installed = listOf(NTFY), saved = NTFY)
        val viewModel = ProfileViewModel(distributors, FakeAuthRepository(), FakeLibraryRepository())

        viewModel.disablePush()

        assertEquals(PushState.Available(listOf(NTFY)), viewModel.pushState.value)
        assertEquals(true, distributors.unregistered)
    }

    @Test
    fun `refresh picks up a distributor installed while the app was in the background`() {
        // The state the NoDistributor prompt creates and must be able to leave. The prompt sends
        // the user out of the app to install ntfy; the ViewModel is scoped to the
        // NavBackStackEntry and survives that trip, so `init` does not run again. If nothing
        // re-reads on the way back, the screen still says "push needs one more app" after the
        // user did exactly what it asked — A-A's failure mode wearing its own prompt.
        val distributors = FakeDistributors()
        val viewModel = ProfileViewModel(distributors, FakeAuthRepository(), FakeLibraryRepository())
        assertEquals(PushState.NoDistributor, viewModel.pushState.value)

        distributors.installed = listOf(NTFY)
        viewModel.refresh()

        assertEquals(PushState.Available(listOf(NTFY)), viewModel.pushState.value)
    }

    @Test
    fun `signing out calls AuthRepository logout`() =
        runTest(dispatcher) {
            // Gap 2, Phase 9a device walkthroughs: AuthRepository.logout() was hardened to delete
            // the server-side push target BEFORE clearing tokens, and none of that was reachable
            // from any screen. This is the regression guard for the door this task adds.
            val authRepository = FakeAuthRepository()
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository, FakeLibraryRepository())

            viewModel.signOut()
            advanceUntilIdle()

            assertTrue(authRepository.logoutCalled)
        }

    @Test
    fun `signedOut flips to true only after logout completes`() =
        runTest(dispatcher) {
            // ProfileScreen's LaunchedEffect navigates away the moment this flips — if it flipped
            // BEFORE logout ran, a slow or failing logout call would never get the chance to run
            // at all, because the screen (and this ViewModel with it) would already be gone.
            //
            // A gate `logout()` suspends on, not a plain fake: with only StandardTestDispatcher +
            // advanceUntilIdle() a test can observe just the END state, and `mutableSignedOut.value
            // = true` moved to BEFORE `authRepository.logout()` would still make that assertion
            // pass. Suspending mid-`logout()` and asserting `signedOut` is still false at that
            // point is what actually falsifies the wrong ordering.
            val gate = CompletableDeferred<Unit>()
            val authRepository = FakeAuthRepository(onLogout = { gate.await() })
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository, FakeLibraryRepository())

            viewModel.signOut()
            advanceUntilIdle()
            assertTrue(authRepository.logoutCalled)
            assertFalse(viewModel.signedOut.value)

            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.signedOut.value)
        }

    @Test
    fun `a failed sign-out does not flip signedOut and surfaces an error instead`() =
        runTest(dispatcher) {
            // logout() can throw for real: tokenStore.clear() sits outside AuthRepositoryImpl's
            // own guards, and a corrupt/unwritable DataStore throws IOException from it. Flipping
            // signedOut anyway would send the user back to the login screen while a valid session
            // is still on the device — a lie a relaunch would immediately expose.
            val authRepository = FakeAuthRepository(onLogout = { throw IOException("token store unwritable") })
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository, FakeLibraryRepository())

            viewModel.signOut()
            advanceUntilIdle()

            assertFalse(viewModel.signedOut.value)
            assertTrue(viewModel.signOutError.value)
        }

    @Test
    fun `retrying a failed sign-out clears the previous error`() =
        runTest(dispatcher) {
            var shouldFail = true
            val authRepository =
                FakeAuthRepository(onLogout = { if (shouldFail) throw IOException("token store unwritable") })
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository, FakeLibraryRepository())
            viewModel.signOut()
            advanceUntilIdle()
            assertTrue(viewModel.signOutError.value)

            shouldFail = false
            viewModel.signOut()
            advanceUntilIdle()

            assertTrue(viewModel.signedOut.value)
            assertFalse(viewModel.signOutError.value)
        }

    /**
     * The two ViewModel-level tests the brief originally named here — "an unrated library shows
     * no average rather than zero" and "the average is labelled with what it is an average of" —
     * were DELETED in round 1 (review finding, blocking 1), not repointed. Both only ever asserted
     * that [ProfileViewModel.statsState] carried the exact [LibraryStats] the fake was constructed
     * with — the pass-through `LibraryStatsUiState.Success(stats)` assignment in [ProfileViewModel.refreshStats]
     * has no logic between the fake and the assertion for either test to discriminate. The actual
     * behaviour those two names describe — which STRING renders for a null average, and that
     * `rated_count` reaches the label the user reads — lives entirely inside `StatsContent` in
     * `ProfileScreen.kt`, which no ViewModel test reaches; `ProfileScreenTest` (this module, round
     * 1) pins both directly against the composed screen instead. Keeping the two ViewModel tests
     * alongside the screen tests would be exactly the padded-suite failure mode the standing
     * instructions warn about: green regardless of what `StatsContent` actually does.
     */
    @Test
    fun `a failed stats load leaves the rest of the profile usable`() =
        runTest(dispatcher) {
            // Decision C-S. Profile already owns push opt-in and sign-out; a stats failure must
            // not take them down with it. Its own error channel.
            val failure = IOException("stats offline")
            val repository = FakeLibraryRepository(statsFailure = failure)
            val distributors = FakeDistributors(installed = listOf(NTFY))
            val authRepository = FakeAuthRepository()
            val viewModel = ProfileViewModel(distributors, authRepository, repository)
            viewModel.refreshStats() // stands in for LifecycleResumeEffect's call — refreshStats()
            // is no longer folded into init{}'s refresh() (round 1, blocking 2).
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Error(failure), viewModel.statsState.value)

            // Push is untouched by the stats failure — including a later refresh() re-triggering
            // it. refresh() no longer touches stats at all (round 1), so this also proves a push
            // toggle does not re-issue the failing stats GET.
            viewModel.enablePush(NTFY)
            assertEquals(PushState.Registered(NTFY), viewModel.pushState.value)
            assertEquals(LibraryStatsUiState.Error(failure), viewModel.statsState.value)

            // Sign-out is untouched too.
            viewModel.signOut()
            advanceUntilIdle()
            assertTrue(authRepository.logoutCalled)
            assertTrue(viewModel.signedOut.value)
        }

    /**
     * Round-1 bug carried forward from `FavoritesViewModel.refresh` (task 9b.4, three fix rounds):
     * writing [LibraryStatsUiState.Loading] unconditionally on every resume blanks a populated
     * stats block to a spinner for the round trip's duration. `repository.statsGate` is what makes
     * the mid-flight state actually observable — a fake that resolves synchronously never shows it.
     */
    @Test
    fun `a resume over an already-populated stats screen keeps the numbers, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val initial =
                LibraryStats(
                    total = 5,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                    averageScore = null,
                    ratedCount = 0,
                )
            val repository = FakeLibraryRepository(initial)
            val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), repository)
            viewModel.refreshStats() // stands in for LifecycleResumeEffect's first call (round 1)
            advanceUntilIdle()
            assertEquals(LibraryStatsUiState.Success(initial), viewModel.statsState.value)

            val updated =
                LibraryStats(
                    total = 6,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 6),
                    averageScore = null,
                    ratedCount = 0,
                )
            repository.statsResult = updated
            repository.statsGate = CompletableDeferred()
            viewModel.refreshStats() // a later resume — refresh() no longer triggers this (round 1)
            advanceUntilIdle()

            // Still the OLD stats, and still Success — never LibraryStatsUiState.Loading — while
            // the network round trip this resume triggered is genuinely still in flight.
            assertEquals(LibraryStatsUiState.Success(initial), viewModel.statsState.value)

            repository.statsGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Success(updated), viewModel.statsState.value)
        }

    /**
     * Round-2 bug carried forward from `FavoritesViewModel.refresh`: a resume's FAILED background
     * re-fetch must not replace a working, populated stats block with
     * [LibraryStatsUiState.Error] wholesale — it marks [LibraryStatsUiState.Success.isStale]
     * instead, driven by `:core:designsystem`'s `StaleDataBanner`.
     */
    @Test
    fun `a failed resume over an already-populated stats screen marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val initial =
                LibraryStats(
                    total = 5,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                    averageScore = null,
                    ratedCount = 0,
                )
            val repository = FakeLibraryRepository(initial)
            val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), repository)
            viewModel.refreshStats() // stands in for LifecycleResumeEffect's first call (round 1)
            advanceUntilIdle()
            assertEquals(LibraryStatsUiState.Success(initial), viewModel.statsState.value)

            val failure = IOException("stats offline")
            repository.statsGate = CompletableDeferred()
            repository.statsFailure = failure
            viewModel.refreshStats() // a later resume — refresh() no longer triggers this (round 1)
            advanceUntilIdle()

            // Still the OLD stats, and still Success — never LibraryStatsUiState.Error — while the
            // failing round trip is genuinely still in flight.
            assertEquals(LibraryStatsUiState.Success(initial), viewModel.statsState.value)

            repository.statsGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Success(initial, isStale = true), viewModel.statsState.value)
        }

    @Test
    fun `a successful resume clears a previous stale mark`() =
        runTest(dispatcher) {
            val initial =
                LibraryStats(
                    total = 5,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                    averageScore = null,
                    ratedCount = 0,
                )
            val repository = FakeLibraryRepository(initial)
            val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), repository)
            viewModel.refreshStats() // stands in for LifecycleResumeEffect's first call (round 1)
            advanceUntilIdle()

            repository.statsFailure = IOException("stats offline")
            viewModel.refreshStats()
            advanceUntilIdle()
            assertEquals(LibraryStatsUiState.Success(initial, isStale = true), viewModel.statsState.value)

            val updated =
                LibraryStats(
                    total = 6,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 6),
                    averageScore = null,
                    ratedCount = 0,
                )
            repository.statsFailure = null
            repository.statsResult = updated
            viewModel.refreshStats()
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Success(updated, isStale = false), viewModel.statsState.value)
        }

    /**
     * Round 1's own regression guard (blocking 2): `init` must stay a push-only, synchronous read
     * — several tests above (e.g. `no installed distributor is reported as NoDistributor`) read
     * `pushState.value` straight after construction with no `advanceUntilIdle()`, which only works
     * if `init` never launches a coroutine. This pins the OTHER half: `init` must NOT also start
     * fetching stats, because `ProfileScreen`'s `LifecycleResumeEffect` already calls
     * [ProfileViewModel.refreshStats] once on the very first composition (the same `Lifecycle`
     * replay `FavoritesViewModel`'s own KDoc measures) — if `init` fetched too, cold start would
     * issue the stats GET twice with no ordering guarantee between them.
     */
    @Test
    fun `init does not fetch stats — only an explicit refreshStats call does`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            ProfileViewModel(FakeDistributors(), FakeAuthRepository(), repository)
            advanceUntilIdle()

            assertEquals(0, repository.statsCalls)
        }

    /**
     * The push-toggle half of the same finding: [ProfileViewModel.enablePush]/[ProfileViewModel.disablePush]
     * call [ProfileViewModel.refresh] to re-read push state, and before round 1 that function also
     * fetched stats — so every push toggle silently re-issued `GET /v1/library/stats`, a regression
     * against pre-9b.5 behaviour. `enablePush`/`disablePush` share one `refresh()` call, so exercising
     * either is enough to pin that neither reaches [LibraryRepository.libraryStats] any more.
     */
    @Test
    fun `toggling push does not fetch stats`() =
        runTest(dispatcher) {
            val distributors = FakeDistributors(installed = listOf(NTFY))
            val repository = FakeLibraryRepository()
            val viewModel = ProfileViewModel(distributors, FakeAuthRepository(), repository)
            advanceUntilIdle()

            viewModel.enablePush(NTFY)
            viewModel.disablePush()
            advanceUntilIdle()

            assertEquals(0, repository.statsCalls)
        }
}
