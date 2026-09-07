package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.model.AuthFailure
import kotlinx.coroutines.CompletableDeferred

/**
 * `GroupDetailViewModel`'s second dependency, as of round 1 review (`AuthRepository.currentUserId`'s
 * own KDoc — identity moved here from `GroupRepository`). Only [currentUserId] is functional; every
 * other member `error(...)`s rather than silently no-op-ing — [FakeGroupRepository]'s identical
 * discrimination technique, one file over.
 *
 * [currentUserIdCalls] and [currentUserIdGate] are what round 1 review's minor 6 asked for
 * explicitly: the OLD `FakeGroupRepository.currentUserId` had neither, so no test could tell
 * "resolved once, cached" apart from "resolved every time a caller asks" — the exact distinction
 * `AuthRepositoryImpl.currentUserId`'s own in-memory cache exists to guarantee.
 */
internal class FakeAuthRepository(
    var currentUserIdResult: String = "user-self",
    var currentUserIdFailure: AuthFailure? = null,
) : AuthRepository {
    var currentUserIdGate: CompletableDeferred<Unit>? = null

    var currentUserIdCalls = 0
        private set

    override suspend fun currentUserId(): String {
        currentUserIdCalls++
        currentUserIdGate?.await()
        currentUserIdFailure?.let { throw it }
        return currentUserIdResult
    }

    override suspend fun hasSession(): Boolean = error("not exercised by GroupDetailViewModel")

    override suspend fun login(
        email: String,
        password: String,
    ): Unit = error("not exercised by GroupDetailViewModel")

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    ): Unit = error("not exercised by GroupDetailViewModel")

    override suspend fun logout(): Unit = error("not exercised by GroupDetailViewModel")
}
