package com.anarky.showtrack.core.data.repository

/**
 * The session. `:app` asks [hasSession] before choosing a start destination — the reactive
 * `AuthEvent.LoggedOut` gate cannot cover a cold start, because a logged-out launch has no token
 * to fail a refresh with and so emits nothing (decision C-F).
 */
interface AuthRepository {
    suspend fun hasSession(): Boolean

    suspend fun login(
        email: String,
        password: String,
    )

    /** Creates the account and signs in. Throws [RegisteredButNotLoggedIn] if only the first half succeeded. */
    suspend fun register(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    )

    suspend fun logout()
}

/**
 * The account exists; the session does not. A distinct type because the recovery differs: the
 * user should sign in, NOT register again — registering again answers "email already taken"
 * (decision C-M).
 */
class RegisteredButNotLoggedIn(
    cause: Throwable,
) : Exception(cause)
