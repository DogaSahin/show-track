package com.anarky.showtrack.feature.auth

import com.anarky.showtrack.core.data.repository.AuthRepository
import javax.inject.Inject

/**
 * A SEPARATE fake from [AuthViewModelTest]'s own private `FakeAuthRepository` — that one is
 * `private` to its file, so [TestDataModule] (a different file) cannot bind it; naming this one
 * distinctly avoids a duplicate-class clash in the same package. Only [login] is functional:
 * [AuthEntryHiltTest] drives a real login submit through `authEntry`, never register.
 *
 * `@Inject constructor()` is what lets [TestDataModule]'s `@Binds` method construct this with no
 * `@Provides` boilerplate — `:feature:library`'s `FakeLibraryRepository` is the pattern.
 */
internal class EntryFakeAuthRepository
    @Inject
    constructor() : AuthRepository {
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
        ) = error("AuthEntryHiltTest only exercises login")

        override suspend fun logout() = error("AuthEntryHiltTest only exercises login")
    }
