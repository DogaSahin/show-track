package com.anarky.showtrack.core.data.alerts

import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertSessionObserverTest {
    @Test
    fun `a session ended by a failed token refresh cancels the alerts, every time`() =
        runTest {
            val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 2)
            val alerts = RecordingAlerts()
            val source =
                object : AuthEventSource {
                    override val authEvents: Flow<AuthEvent> get() = events
                }
            AlertSessionObserver(source, alerts).start(TestScope(testScheduler))
            testScheduler.runCurrent()

            events.emit(AuthEvent.LoggedOut)
            events.emit(AuthEvent.LoggedOut)
            testScheduler.runCurrent()

            assertEquals(2, alerts.cancelCalls)
        }

    private class RecordingAlerts : EpisodeAlerts {
        var cancelCalls = 0

        override suspend fun requestSync() = Unit

        override suspend fun cancelAll() {
            cancelCalls++
        }
    }
}
