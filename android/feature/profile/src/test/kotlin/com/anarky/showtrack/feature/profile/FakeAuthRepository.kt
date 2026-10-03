package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.model.CurrentUser
import java.time.Instant

/**
 * Shared by [ProfileViewModelTest] and [ProfileResumeTest] — both construct a [ProfileViewModel].
 * Exercised against a fake, the same way `AuthViewModelTest`'s `FakeAuthRepository` is used.
 * [onLogout] runs AFTER [logoutCalled] is recorded but BEFORE `logout()` returns, so a test can
 * make it suspend (to observe `signOut()` mid-flight) or throw (to exercise the failure guard).
 */
internal class FakeAuthRepository(
    private val onLogout: suspend () -> Unit = {},
    var currentUserFailure: Throwable? = null,
) : AuthRepository {
    var logoutCalled: Boolean = false

    override suspend fun hasSession(): Boolean = true

    override suspend fun currentUserId(): String = error("not exercised by ProfileViewModelTest/ProfileResumeTest")

    override suspend fun currentUser(): CurrentUser {
        currentUserFailure?.let { throw it }
        return USER
    }

    override suspend fun login(
        email: String,
        password: String,
    ) = Unit

    override suspend fun register(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    ) = Unit

    override suspend fun logout() {
        logoutCalled = true
        onLogout()
    }

    companion object {
        val USER =
            CurrentUser(
                id = "user-1",
                username = "deniz",
                email = "deniz@example.test",
                createdAt = Instant.parse("2025-03-14T10:00:00Z"),
            )
    }
}
