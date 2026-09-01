package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.feature.profile.push.DistributorSource
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

/** Exercised against a fake, the same way `AuthViewModelTest`'s `FakeAuthRepository` is used. */
private class FakeAuthRepository : AuthRepository {
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
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
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
            val authRepository = FakeAuthRepository()
            val viewModel = ProfileViewModel(FakeDistributors(), authRepository)
            assertFalse(viewModel.signedOut.value)

            viewModel.signOut()
            advanceUntilIdle()

            assertTrue(viewModel.signedOut.value)
        }
}
