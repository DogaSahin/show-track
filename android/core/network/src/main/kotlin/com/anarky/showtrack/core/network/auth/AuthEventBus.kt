package com.anarky.showtrack.core.network.auth

import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way channel from the network layer to whatever routes on auth state (decision A-J).
 *
 * `replay = 0` deliberately: a replayed LoggedOut would be re-delivered to a collector that
 * subscribes after a successful re-login, and tell it the user is logged out when they are not.
 * The cost is that an event emitted before :app subscribes is dropped — acceptable, because the
 * subscription is set up at startup, before any request can 401.
 *
 * [emit] is not `suspend` on purpose: it is called from inside the authenticator's mutex, and a
 * suspending emit would hold that lock waiting on a slow collector. `extraBufferCapacity = 1`
 * plus DROP_OLDEST is what makes `tryEmit` always succeed.
 */
@Singleton
class AuthEventBus
    @Inject
    constructor() {
        private val mutableEvents =
            MutableSharedFlow<AuthEvent>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val events: SharedFlow<AuthEvent> = mutableEvents.asSharedFlow()

        fun emit(event: AuthEvent) {
            mutableEvents.tryEmit(event)
        }
    }
