package com.anarky.showtrack

import com.anarky.showtrack.core.data.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [AppViewModel.start]'s own state-transition logic, isolated from the Compose/`NavHost`
 * mechanism `ShowTrackGraphRebuildTest` covers — the other half of review finding 1 (task 9b.0):
 * that the SIGNAL moves correctly is a plain ViewModel concern with no Compose or `NavGraph`
 * involved at all, and had no test of its own before this.
 *
 * `start` is a plain `MutableStateFlow` (decision C-U), so `.value` is read directly — no
 * `WhileSubscribed` subscription gate to work around here, unlike `LibraryViewModel.state`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts Undecided before the session check resolves`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = true))

            assertEquals(AppStart.Undecided, viewModel.start.value)
        }

    @Test
    fun `resolves to Library when a session already exists`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = true))

            advanceUntilIdle()

            assertEquals(AppStart.Library, viewModel.start.value)
        }

    @Test
    fun `resolves to Auth when no session exists`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = false))

            advanceUntilIdle()

            assertEquals(AppStart.Auth, viewModel.start.value)
        }

    @Test
    fun `markSignedIn promotes an Auth-started session to Library when not a new account`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = false))
            advanceUntilIdle()
            assertEquals(AppStart.Auth, viewModel.start.value)

            viewModel.markSignedIn(isNewAccount = false)

            assertEquals(AppStart.Library, viewModel.start.value)
        }

    /**
     * Task 9b.6, round 1 fix: the case a fresh registration reaches, and the whole reason
     * [AppStart.Onboarding] exists — see [AppViewModel.markSignedIn]'s own KDoc for why this could
     * not be a boolean riding along with [AppStart.Library] instead.
     */
    @Test
    fun `markSignedIn promotes an Auth-started session to Onboarding when a new account`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = false))
            advanceUntilIdle()
            assertEquals(AppStart.Auth, viewModel.start.value)

            viewModel.markSignedIn(isNewAccount = true)

            assertEquals(AppStart.Onboarding, viewModel.start.value)
        }

    /**
     * The other half of `routeShowTrackNavigation`'s `LibraryRoute` branch (round 1, task 9b.6 fix
     * round): finishing onboarding calls this with `isNewAccount = false`, the same argument value
     * an ordinary login uses, and it must move `start` on to `Library` from `Onboarding` just as
     * readily as it does from `Auth`.
     */
    @Test
    fun `markSignedIn promotes an Onboarding session to Library when onboarding finishes`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = false))
            advanceUntilIdle()
            viewModel.markSignedIn(isNewAccount = true)
            assertEquals(AppStart.Onboarding, viewModel.start.value)

            viewModel.markSignedIn(isNewAccount = false)

            assertEquals(AppStart.Library, viewModel.start.value)
        }

    /**
     * The case a second login (after a mid-session logout) reaches: `start` is already `Library`,
     * and this must stay a no-op rather than doing anything surprising — same-value `StateFlow`
     * writes don't re-emit, so nothing downstream (`ShowTrackNavHost`'s `when`) recomposes either.
     */
    @Test
    fun `markSignedIn is idempotent once already Library`() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeAuthRepository(hasSession = true))
            advanceUntilIdle()
            assertEquals(AppStart.Library, viewModel.start.value)

            viewModel.markSignedIn(isNewAccount = false)

            assertEquals(AppStart.Library, viewModel.start.value)
        }

    private class FakeAuthRepository(
        private val hasSession: Boolean,
    ) : AuthRepository {
        override suspend fun hasSession(): Boolean = hasSession

        override suspend fun login(
            email: String,
            password: String,
        ): Unit = error("not exercised by AppViewModelTest")

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ): Unit = error("not exercised by AppViewModelTest")

        override suspend fun logout(): Unit = error("not exercised by AppViewModelTest")
    }
}
