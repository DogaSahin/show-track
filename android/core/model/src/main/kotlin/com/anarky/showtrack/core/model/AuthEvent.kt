package com.anarky.showtrack.core.model

/** Emitted by :core:network when a refresh fails terminally. :app routes on it (decision A-J). */
sealed interface AuthEvent {
    data object LoggedOut : AuthEvent
}
