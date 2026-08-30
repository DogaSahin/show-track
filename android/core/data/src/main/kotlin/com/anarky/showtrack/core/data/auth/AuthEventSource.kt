package com.anarky.showtrack.core.data.auth

import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.network.auth.AuthEventBus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The auth-state half of the single door.
 *
 * `AuthEventBus` lives in `:core:network`, and `:app` could inject it directly — architecture
 * rule 2 constrains `:feature:*` modules, not the composition root. It is re-exposed here anyway,
 * because the rule's *claim* is that `:core:data` is the only module aware of Retrofit and Room,
 * and that claim stops being true the moment the app shell names a network type. One delegating
 * property keeps it true. No domain type leaks either way: [AuthEvent] is declared in
 * `:core:model`, which every module already sees.
 *
 * An interface rather than a `@Provides Flow<AuthEvent>`: a bare `Flow<AuthEvent>` is a binding
 * key with no name on it, so a second flow of the same type could never be added, and nothing at
 * the injection site says what it carries.
 */
interface AuthEventSource {
    /** Terminal auth failures from the network layer. Hot, `replay = 0` — see `AuthEventBus`. */
    val authEvents: Flow<AuthEvent>
}

@Singleton
class AuthEventSourceImpl
    @Inject
    constructor(
        private val bus: AuthEventBus,
    ) : AuthEventSource {
        override val authEvents: Flow<AuthEvent> get() = bus.events
    }
