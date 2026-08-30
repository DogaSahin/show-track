package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.feature.profile.push.DistributorSource
import org.junit.Assert.assertEquals
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

class ProfileViewModelTest {
    @Test
    fun `no installed distributor is reported as NoDistributor`() {
        // The state the whole prompt exists for. A user here receives nothing, forever, and the
        // only conclusion available without the prompt is "push is broken" rather than "push
        // needs one more app" (decision A-A).
        val state = ProfileViewModel(FakeDistributors()).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `an installed but unchosen distributor is offered`() {
        val state = ProfileViewModel(FakeDistributors(installed = listOf(NTFY))).pushState.value

        assertEquals(PushState.Available(listOf(NTFY)), state)
    }

    @Test
    fun `choosing a distributor moves to Registered`() {
        val viewModel = ProfileViewModel(FakeDistributors(installed = listOf(NTFY)))

        viewModel.enablePush(NTFY)

        assertEquals(PushState.Registered(NTFY), viewModel.pushState.value)
    }

    @Test
    fun `a saved distributor that is no longer installed is not reported as registered`() {
        // The connector keeps the saved choice after the app is uninstalled. Trusting it would
        // show "episode alerts are on" for an app that is gone — the silent failure this state
        // machine exists to prevent, wearing a green tick.
        val state =
            ProfileViewModel(FakeDistributors(installed = emptyList(), saved = NTFY)).pushState.value

        assertEquals(PushState.NoDistributor, state)
    }

    @Test
    fun `a saved distributor is ignored when a DIFFERENT one is installed`() {
        val state =
            ProfileViewModel(
                FakeDistributors(installed = listOf("org.other.distributor"), saved = NTFY),
            ).pushState.value

        assertEquals(PushState.Available(listOf("org.other.distributor")), state)
    }

    @Test
    fun `turning push off returns to Available rather than NoDistributor`() {
        // The distinction matters to the user: the app is still installed, so the screen must
        // offer to turn it back on rather than tell them to go and install something.
        val distributors = FakeDistributors(installed = listOf(NTFY), saved = NTFY)
        val viewModel = ProfileViewModel(distributors)

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
        val viewModel = ProfileViewModel(distributors)
        assertEquals(PushState.NoDistributor, viewModel.pushState.value)

        distributors.installed = listOf(NTFY)
        viewModel.refresh()

        assertEquals(PushState.Available(listOf(NTFY)), viewModel.pushState.value)
    }
}
