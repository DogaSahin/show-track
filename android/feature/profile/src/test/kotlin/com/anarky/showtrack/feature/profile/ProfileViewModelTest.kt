package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.feature.profile.push.DistributorSource
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
        val state = ProfileViewModel(FakeDistributors(), FakeAuthRepository()).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `an installed but unchosen distributor is offered`() {
        val state =
            ProfileViewModel(FakeDistributors(installed = listOf(NTFY)), FakeAuthRepository()).pushState.value

        assertEquals(PushState.Available(listOf(NTFY)), state)
    }

    @Test
    fun `choosing a distributor moves to Registered`() {
        val viewModel = ProfileViewModel(FakeDistributors(installed = listOf(NTFY)), FakeAuthRepository())

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
            ).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `a saved distributor is ignored when a DIFFERENT one is installed`() {
        val state =
            ProfileViewModel(
                FakeDistributors(installed = listOf("org.other.distributor"), saved = NTFY),
                FakeAuthRepository(),
            ).pushState.value

        assertEquals(PushState.Available(listOf("org.other.distributor")), state)
    }

    @Test
    fun `turning push off returns to Available rather than NoDistributor`() {
        // The distinction matters to the user: the app is still installed, so the screen must
        // offer to turn it back on rather than tell them to go and install something.
        val distributors = FakeDistributors(installed = listOf(NTFY), saved = NTFY)
        val viewModel = ProfileViewModel(distributors, FakeAuthRepository())

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
        val viewModel = ProfileViewModel(distributors, FakeAuthRepository())
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
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository)

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
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository)

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
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository)

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
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository)
            viewModel.signOut()
            advanceUntilIdle()
            assertTrue(viewModel.signOutError.value)

            shouldFail = false
            viewModel.signOut()
            advanceUntilIdle()

            assertTrue(viewModel.signedOut.value)
            assertFalse(viewModel.signOutError.value)
        }
}
