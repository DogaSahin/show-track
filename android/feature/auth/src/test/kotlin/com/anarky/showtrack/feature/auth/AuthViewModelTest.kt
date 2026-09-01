package com.anarky.showtrack.feature.auth

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.RegisteredButNotLoggedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Exercised against a FAKE `AuthRepository`, the same way `LibraryViewModelTest` exercises
 * `LibraryViewModel`: nothing here knows Retrofit exists, and none of it needs a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the launch inside `submit` run at all.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a successful login moves to Authenticated`() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = AuthViewModel(repository)

            viewModel.submitLogin("a@example.com", "hunter2hunter2")
            advanceUntilIdle()

            assertEquals(AuthUiState.Authenticated, viewModel.state.value)
        }

    @Test
    fun `an account created without a session says exactly that`() =
        runTest(dispatcher) {
            // The distinction C-M exists for. Collapsing this into RegistrationRefused sends the
            // user back to a form that cannot succeed.
            val repository = FakeAuthRepository(registerOutcome = RegisteredButNotLoggedIn(IOException()))
            val viewModel = AuthViewModel(repository)

            viewModel.submitRegister("someone", "a@example.com", "hunter2hunter2", "CODE")
            advanceUntilIdle()

            assertEquals(
                AuthUiState.Form(mode = AuthMode.REGISTER, error = AuthError.AccountCreatedNotSignedIn),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failed submit clears the submitting flag`() =
        runTest(dispatcher) {
            // Without this the button stays disabled forever and the screen is dead.
            val repository = FakeAuthRepository(loginFailure = IOException("offline"))
            val viewModel = AuthViewModel(repository)

            viewModel.submitLogin("a@example.com", "wrong")
            advanceUntilIdle()

            assertFalse((viewModel.state.value as AuthUiState.Form).submitting)
        }

    @Test
    fun `switching mode clears the previous error`() =
        runTest(dispatcher) {
            // An error surfaced while logging in must not haunt the register form once the user
            // switches — a stale error attached to the wrong form is worse than no error at all.
            val repository = FakeAuthRepository(loginFailure = IOException("offline"))
            val viewModel = AuthViewModel(repository)

            viewModel.submitLogin("a@example.com", "wrong")
            advanceUntilIdle()
            assertEquals(AuthError.Unknown, (viewModel.state.value as AuthUiState.Form).error)

            viewModel.setMode(AuthMode.REGISTER)

            assertEquals(AuthUiState.Form(mode = AuthMode.REGISTER, error = null), viewModel.state.value)
        }

    private class FakeAuthRepository(
        var loginFailure: Throwable? = null,
        var registerOutcome: Throwable? = null,
    ) : AuthRepository {
        override suspend fun hasSession(): Boolean = true

        override suspend fun login(
            email: String,
            password: String,
        ) {
            loginFailure?.let { throw it }
        }

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ) {
            registerOutcome?.let { throw it }
        }

        override suspend fun logout() = Unit
    }
}
