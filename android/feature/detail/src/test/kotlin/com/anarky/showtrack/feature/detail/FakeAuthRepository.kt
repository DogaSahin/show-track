package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.model.AuthFailure
import kotlinx.coroutines.CompletableDeferred

/**
 * [DetailViewModel]'s second dependency (task 9c.7 — `findOwnReview`'s own KDoc). Only
 * [currentUserId] is functional; every other member `error(...)`s — [FakeGroupRepository]'s
 * identical discrimination technique, one file over. `GroupDetailViewModelTest`'s own
 * `FakeAuthRepository` shape, one module over.
 *
 * [currentUserIdGate] lets a test hold [currentUserId] suspended — needed to construct "the
 * editor opens before identity has resolved yet" ([findOwnReview]'s own honest-null case) without
 * a race on real wall-clock timing.
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

    override suspend fun hasSession(): Boolean = error("not exercised by DetailViewModel")

    override suspend fun login(
        email: String,
        password: String,
    ): Unit = error("not exercised by DetailViewModel")

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    ): Unit = error("not exercised by DetailViewModel")

    override suspend fun logout(): Unit = error("not exercised by DetailViewModel")
}
