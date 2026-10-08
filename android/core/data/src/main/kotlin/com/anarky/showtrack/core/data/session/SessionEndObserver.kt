package com.anarky.showtrack.core.data.session

import com.anarky.showtrack.core.data.alerts.EpisodeAlerts
import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A session can end without logout(): TokenRefreshAuthenticator clears the tokens on an
 * unrecoverable 401 and emits [AuthEvent.LoggedOut]. That ending gets the same clean-up as a
 * sign-out: the account's alerts are cancelled and its data leaves the phone. Started once, by
 * the Application.
 */
@Singleton
class SessionEndObserver
    @Inject
    constructor(
        private val authEvents: AuthEventSource,
        private val alerts: EpisodeAlerts,
        private val userData: UserDataCleaner,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                authEvents.authEvents.collect { event ->
                    when (event) {
                        AuthEvent.LoggedOut -> {
                            alerts.cancelAll()
                            userData.clear()
                        }
                    }
                }
            }
        }
    }
