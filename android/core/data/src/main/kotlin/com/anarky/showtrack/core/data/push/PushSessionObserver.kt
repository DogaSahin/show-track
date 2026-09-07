package com.anarky.showtrack.core.data.push

import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears this device's push registration when the session ends.
 *
 * Its own class rather than a `collect` written inline in `ShowTrackApplication`, for the reason
 * `PushRegistrar` is its own class: an `Application` cannot be constructed by a JVM test, so
 * anything living inside one is checkable only by reading — and what has to be checked here is
 * that `LoggedOut` really does reach [PushRepository.onLoggedOut]. That wiring is the whole fix;
 * without it a shared device leaks one account's notifications to the next.
 *
 * The scope is passed in rather than held: the lifetime belongs to the process, and a scope
 * created here would be a second, invisible one that nothing can cancel in a test.
 *
 * `AuthEventBus` has `replay = 0`, so [start] must run before anything can 401 — which is why
 * `ShowTrackApplication.onCreate` calls it rather than any screen.
 */
@Singleton
class PushSessionObserver
    @Inject
    constructor(
        private val authEvents: AuthEventSource,
        private val push: PushRepository,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                authEvents.authEvents.collect { event ->
                    when (event) {
                        // `when` on the sealed type with no `else`, so a second AuthEvent added in
                        // a later phase is a compile error here rather than a silently ignored one.
                        AuthEvent.LoggedOut -> push.onLoggedOut()
                    }
                }
            }
        }
    }
