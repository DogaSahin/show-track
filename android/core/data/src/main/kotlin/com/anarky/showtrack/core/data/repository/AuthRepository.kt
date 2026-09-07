package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.AuthFailure

/**
 * The session. `:app` asks [hasSession] before choosing a start destination — the reactive
 * `AuthEvent.LoggedOut` gate cannot cover a cold start, because a logged-out launch has no token
 * to fail a refresh with and so emits nothing (decision C-F).
 */
interface AuthRepository {
    suspend fun hasSession(): Boolean

    /**
     * `GET /v1/users/me`, answering the signed-in user's own id (task 9c.2 round 1 — moved here
     * from `GroupRepository` on review; see this file's own note below for why). Resolved
     * INDEPENDENTLY of any screen-scoped load: identity is a session-lifetime fact, cached in
     * memory here for the life of the session and cleared on [logout] — a caller that asks twice
     * pays for one network round trip, not two. Throws [com.anarky.showtrack.core.model.AuthFailure]
     * on failure; unlike [login]/[register] there is no "wrong credentials" case, so only
     * [com.anarky.showtrack.core.model.AuthFailure.Offline]/[com.anarky.showtrack.core.model.AuthFailure.Unexpected]
     * are ever produced.
     *
     * **Why this lives on `AuthRepository`, not `GroupRepository`** (round 1 review finding,
     * BLOCKING 2/3 and the ruling that resolved it): the original placement fetched this identity
     * INSIDE THE SAME `try` as the group's member list, which meant a failed member-list load left
     * no id in existence at all — every action needing it (leaving the group) died silently with
     * the load, even though leaving has nothing to do with whether the member list loaded. C-S says
     * one error channel PER OPERATION, not one failure TYPE per screen; collapsing "which type do I
     * catch" into "which screen am I on" is what produced the bug. Identity is a session-lifetime
     * fact; the member list is a per-screen, per-refresh fact — fusing them made the session fact
     * unavailable exactly when the screen fact failed. It is also the fact the NEXT things this
     * phase builds (a "you" badge on feed actors, "your review" on a shared title, your own column
     * in progress comparison) will all need, and none of them has any reason to import
     * `GroupRepository` to ask a non-group question.
     */
    suspend fun currentUserId(): String

    /** Throws [AuthFailure] — `:feature:auth` catches its cases to tell a wrong password from being offline. */
    suspend fun login(
        email: String,
        password: String,
    )

    /**
     * Creates the account and signs in. Throws [AuthFailure] if the account itself could not be
     * created, or [RegisteredButNotLoggedIn] if it was created but the follow-up login failed.
     */
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
