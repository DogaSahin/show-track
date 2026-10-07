package com.anarky.showtrack.core.data.alerts

import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A session can end without logout(): TokenRefreshAuthenticator clears the tokens on an
 * unrecoverable 401 and emits [AuthEvent.LoggedOut]. Alerts scheduled for that account are
 * cancelled then too, so nothing fires for a signed-out phone. Started once, by the Application.
 */
@Singleton
class AlertSessionObserver
    @Inject
    constructor(
        private val authEvents: AuthEventSource,
        private val alerts: EpisodeAlerts,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                authEvents.authEvents.collect { event ->
                    when (event) {
                        AuthEvent.LoggedOut -> alerts.cancelAll()
                    }
                }
            }
        }
    }
