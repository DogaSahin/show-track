package com.anarky.showtrack.core.data.push

import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.model.PushNotification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingPush : PushRepository {
    var loggedOutCalls = 0

    override suspend fun register(endpoint: String) = Unit

    override suspend fun unregister() = Unit

    override suspend fun onLoggedOut() {
        loggedOutCalls++
    }

    override fun decodeMessage(body: ByteArray): PushNotification? = null
}

/**
 * The wiring, asserted rather than read. Without this, `PushSessionObserver` could stop calling
 * `onLoggedOut` — or never be started — and every repository test above would still pass while a
 * shared device leaked one account's notifications to the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushSessionObserverTest {
    @Test
    fun `a logout clears the push registration`() =
        runTest {
            val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
            val push = RecordingPush()
            PushSessionObserver(source(events), push).start(TestScope(testScheduler))
            testScheduler.runCurrent()

            events.emit(AuthEvent.LoggedOut)
            testScheduler.runCurrent()

            assertEquals(1, push.loggedOutCalls)
        }

    @Test
    fun `the collector survives a first event and keeps listening`() =
        runTest {
            // A `collect` that terminated after one emission would pass the test above and leave
            // the SECOND handover on a device unprotected — which is the one nobody would test by
            // hand.
            val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 2)
            val push = RecordingPush()
            PushSessionObserver(source(events), push).start(TestScope(testScheduler))
            testScheduler.runCurrent()

            events.emit(AuthEvent.LoggedOut)
            events.emit(AuthEvent.LoggedOut)
            testScheduler.runCurrent()

            assertEquals(2, push.loggedOutCalls)
        }

    private fun source(events: Flow<AuthEvent>) =
        object : AuthEventSource {
            override val authEvents: Flow<AuthEvent> get() = events
        }
}
