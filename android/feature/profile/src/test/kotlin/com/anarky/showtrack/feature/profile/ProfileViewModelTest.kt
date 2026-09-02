package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.feature.profile.push.DistributorSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal

private const val NTFY = "io.heckel.ntfy"

private class FakeDistributors(
    var installed: List<String> = emptyList(),
    var saved: String? = null,
) : DistributorSource {
    var unregistered = false

    override fun available(): List<String> = installed

    override fun selected(): String? = saved

    override fun register(packageName: String) {
        saved = packageName
    }

    override fun unregister() {
        saved = null
        unregistered = true
    }
}

/**
 * Exercised against a fake, the same way `AuthViewModelTest`'s `FakeAuthRepository` is used.
 * [onLogout] runs AFTER [logoutCalled] is recorded but BEFORE `logout()` returns, so a test can
 * make it suspend (to observe `signOut()` mid-flight) or throw (to exercise the failure guard).
 */
private class FakeAuthRepository(
    private val onLogout: suspend () -> Unit = {},
) : AuthRepository {
    var logoutCalled: Boolean = false

    override suspend fun hasSession(): Boolean = true

    override suspend fun login(
        email: String,
        password: String,
    ) = Unit

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    ) = Unit

    override suspend fun logout() {
        logoutCalled = true
        onLogout()
    }
}

/**
 * Only [libraryStats] is functional — every other member `error(...)`, the same discrimination
 * `:feature:favorites`' own `FakeLibraryRepository` uses: a [ProfileViewModel] that accidentally
 * reached the general library surface instead of [LibraryRepository.libraryStats] fails LOUDLY,
 * with that message, rather than silently returning the wrong thing.
 *
 * [statsGate], when set, is what lets a test observe [ProfileViewModel.statsState] WHILE
 * [libraryStats] is suspended — mirroring `FavoritesViewModelTest`'s `FakeLibraryRepository.refreshGate`,
 * needed for the identical reason: a fake that always resolves synchronously can never make a
 * wrongly-shown [LibraryStatsUiState.Loading] (or a wrongly-replaced [LibraryStatsUiState.Error])
 * observable mid-flight.
 */
private class FakeLibraryRepository(
    var statsResult: LibraryStats = EMPTY_STATS,
    var statsFailure: Throwable? = null,
) : LibraryRepository {
    var statsGate: CompletableDeferred<Unit>? = null

    override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by ProfileViewModel")

    override suspend fun refresh(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun loadMore(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by ProfileViewModel")

    override suspend fun add(
        source: MediaSource,
        externalId: String,
    ): LibraryEntry = error("not exercised by ProfileViewModel")

    override suspend fun update(
        entryId: String,
        patch: LibraryPatch,
    ): LibraryEntry = error("not exercised by ProfileViewModel")

    override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by ProfileViewModel")

    override val favoriteEntries: StateFlow<List<LibraryEntry>> =
        MutableStateFlow(emptyList<LibraryEntry>()).asStateFlow()

    override suspend fun refreshFavorites(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun loadMoreFavorites(): Unit = error("not exercised by ProfileViewModel")

    override suspend fun libraryStats(): LibraryStats {
        statsGate?.await()
        statsFailure?.let { throw it }
        return statsResult
    }

    private companion object {
        val EMPTY_STATS = LibraryStats(total = 0, byStatus = emptyMap(), averageScore = null, ratedCount = 0)
    }
}

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

    @Test
    fun `an unrated library shows no average rather than zero`() =
        runTest(dispatcher) {
            // average_score null → the UI must not render 0.0. "You rate everything zero" is a
            // different and wrong statement.
            val stats =
                LibraryStats(
                    total = 5,
                    byStatus = mapOf(UserMediaStatus.WATCHING to 5),
                    averageScore = null,
                    ratedCount = 0,
                )
            val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), FakeLibraryRepository(stats))
            advanceUntilIdle()

            val success = viewModel.statsState.value as LibraryStatsUiState.Success
            assertNull(success.stats.averageScore)
        }

    @Test
    fun `the average is labelled with what it is an average of`() =
        runTest(dispatcher) {
            // rated_count travels with average_score precisely so the screen can say "8.4 across
            // 12 rated titles". An average over 12 of 400 is not "your average score".
            val stats =
                LibraryStats(total = 400, byStatus = emptyMap(), averageScore = BigDecimal("8.4"), ratedCount = 12)
            val viewModel = ProfileViewModel(FakeDistributors(), FakeAuthRepository(), FakeLibraryRepository(stats))
            advanceUntilIdle()

            val success = viewModel.statsState.value as LibraryStatsUiState.Success
            assertEquals(BigDecimal("8.4"), success.stats.averageScore)
            assertEquals(12, success.stats.ratedCount)
        }

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
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Error(failure), viewModel.statsState.value)

            // Push is untouched by the stats failure — including a later refresh() re-triggering it.
            viewModel.enablePush(NTFY)
            assertEquals(PushState.Registered(NTFY), viewModel.pushState.value)

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
            viewModel.refresh()
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
            advanceUntilIdle()
            assertEquals(LibraryStatsUiState.Success(initial), viewModel.statsState.value)

            val failure = IOException("stats offline")
            repository.statsGate = CompletableDeferred()
            repository.statsFailure = failure
            viewModel.refresh()
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
            advanceUntilIdle()

            repository.statsFailure = IOException("stats offline")
            viewModel.refresh()
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
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(LibraryStatsUiState.Success(updated, isStale = false), viewModel.statsState.value)
        }
}
