package com.anarky.showtrack.feature.auth

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.RegisteredButNotLoggedIn
import com.anarky.showtrack.core.model.AuthFailure
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
    fun `a rejected invite code says exactly that, not that the email is taken`() =
        runTest(dispatcher) {
            // 400 from POST /v1/auth/register — backend/app/users/service.py's
            // RegistrationError(400, "invalid invite code"). The advice is "get a working
            // code", the opposite of the 409 case below.
            val repository =
                FakeAuthRepository(registerOutcome = AuthFailure.Refused(HTTP_INVITE_CODE_REJECTED, IOException()))
            val viewModel = AuthViewModel(repository)

            viewModel.submitRegister("someone", "a@example.com", "hunter2hunter2", "BADCODE")
            advanceUntilIdle()

            assertEquals(
                AuthUiState.Form(mode = AuthMode.REGISTER, error = AuthError.InviteCodeRejected),
                viewModel.state.value,
            )
        }

    @Test
    fun `a taken email or username points at signing in, not at the invite code`() =
        runTest(dispatcher) {
            // 409 from POST /v1/auth/register — backend/app/users/service.py's
            // RegistrationError(409, "username or email already registered"). Collapsing this
            // into InviteCodeRejected would send a user with a perfectly valid code hunting for
            // a new one that can never fix the actual problem.
            val repository =
                FakeAuthRepository(registerOutcome = AuthFailure.Refused(HTTP_EMAIL_OR_USERNAME_TAKEN, IOException()))
            val viewModel = AuthViewModel(repository)

            viewModel.submitRegister("someone", "a@example.com", "hunter2hunter2", "CODE")
            advanceUntilIdle()

            assertEquals(
                AuthUiState.Form(mode = AuthMode.REGISTER, error = AuthError.EmailOrUsernameTaken),
                viewModel.state.value,
            )
        }

    @Test
    fun `any other refusal status falls back to the generic case`() =
        runTest(dispatcher) {
            // The server defines only 400 and 409 for register. Anything else (a stray 422,
            // say) must not be silently mistaken for one of the two specific cases above.
            val repository = FakeAuthRepository(registerOutcome = AuthFailure.Refused(422, IOException()))
            val viewModel = AuthViewModel(repository)

            viewModel.submitRegister("someone", "a@example.com", "hunter2hunter2", "CODE")
            advanceUntilIdle()

            assertEquals(
                AuthUiState.Form(mode = AuthMode.REGISTER, error = AuthError.RegistrationRefused),
                viewModel.state.value,
            )
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

    private companion object {
        const val HTTP_INVITE_CODE_REJECTED = 400
        const val HTTP_EMAIL_OR_USERNAME_TAKEN = 409
    }
}
