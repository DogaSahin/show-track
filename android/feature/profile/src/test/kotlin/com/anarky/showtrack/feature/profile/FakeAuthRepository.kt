package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.repository.AuthRepository

/**
 * Shared by [ProfileViewModelTest] and [ProfileResumeTest] — both construct a [ProfileViewModel].
 * Exercised against a fake, the same way `AuthViewModelTest`'s `FakeAuthRepository` is used.
 * [onLogout] runs AFTER [logoutCalled] is recorded but BEFORE `logout()` returns, so a test can
 * make it suspend (to observe `signOut()` mid-flight) or throw (to exercise the failure guard).
 */
internal class FakeAuthRepository(
    private val onLogout: suspend () -> Unit = {},
) : AuthRepository {
    var logoutCalled: Boolean = false

    override suspend fun hasSession(): Boolean = true

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
}
