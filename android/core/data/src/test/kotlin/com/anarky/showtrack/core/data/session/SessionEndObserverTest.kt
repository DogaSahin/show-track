package com.anarky.showtrack.core.data.session

import com.anarky.showtrack.core.data.alerts.EpisodeAlerts
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
class SessionEndObserverTest {
    @Test
    fun `a session ended by a failed token refresh cancels alerts and clears the data, every time`() =
        runTest {
            val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 2)
            val calls = mutableListOf<String>()
            val alerts =
                object : EpisodeAlerts {
                    override suspend fun requestSync() = Unit

                    override suspend fun cancelAll() {
                        calls += "alerts"
                    }
                }
            val data =
                object : UserData {
                    override suspend fun clearUserData() {
                        calls += "data"
                    }
                }
            val source =
                object : AuthEventSource {
                    override val authEvents: Flow<AuthEvent> get() = events
                }
            SessionEndObserver(source, alerts, UserDataCleaner(setOf(data))).start(TestScope(testScheduler))
            testScheduler.runCurrent()

            events.emit(AuthEvent.LoggedOut)
            events.emit(AuthEvent.LoggedOut)
            testScheduler.runCurrent()

            assertEquals(listOf("alerts", "data", "alerts", "data"), calls)
        }
}
