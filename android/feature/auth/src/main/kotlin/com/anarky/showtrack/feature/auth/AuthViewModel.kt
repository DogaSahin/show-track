package com.anarky.showtrack.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.RegisteredButNotLoggedIn
import com.anarky.showtrack.core.model.AuthFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The constructor names one interface from `:core:data` and nothing else — architecture rule 2
 * enforced by the compile classpath, the same story as `LibraryViewModel`.
 *
 * No use-case layer between this and the repository (owner's standing guidance): a use case per
 * method would be one class each forwarding a single call.
 */
@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val repository: AuthRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.Form())
        val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

        /** Also clears any error from the form being left behind — see [AuthError]'s doc. */
        fun setMode(mode: AuthMode) {
            mutableState.value = AuthUiState.Form(mode = mode)
        }

        fun submitLogin(
            email: String,
            password: String,
        ) = submit(AuthMode.LOGIN) { repository.login(email, password) }

        fun submitRegister(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ) = submit(AuthMode.REGISTER) { repository.register(username, email, password, inviteCode) }

        /**
         * `try`/`catch(Exception)` and not `runCatching`: runCatching swallows
         * [CancellationException] as well, which is structured concurrency's own control flow.
         * Catching `Exception` rather than `Throwable` leaves Errors alone.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun submit(
            mode: AuthMode,
            block: suspend () -> Unit,
        ) {
            mutableState.value = AuthUiState.Form(mode = mode, submitting = true)
            viewModelScope.launch {
                try {
                    block()
                    mutableState.value = AuthUiState.Authenticated
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.value = AuthUiState.Form(mode = mode, error = failure.toAuthError())
                }
            }
        }
    }

private fun Throwable.toAuthError(): AuthError =
    when (this) {
        // RegisteredButNotLoggedIn is a SIBLING of AuthFailure, not a subclass — it wraps one
        // as its `cause`. So it needs its own branch: omit it and it falls through to
        // `else -> Unknown`, collapsing decision C-M's distinction into a generic error and
        // sending the user back to a registration form that now answers "email already taken".
        // (Branch ORDER is irrelevant here precisely because the types are disjoint.)
        is RegisteredButNotLoggedIn -> AuthError.AccountCreatedNotSignedIn
        is AuthFailure.InvalidCredentials -> AuthError.InvalidCredentials
        is AuthFailure.Offline -> AuthError.Offline
        // Investigated per Step 1: backend/app/users/service.py's RegistrationError raises 400
        // for a bad invite code (register_user) and 409 for a taken username/email
        // (create_account's IntegrityError catch) — two DIFFERENT statuses, not a collision.
        // RegisterRequest's password floor (schemas.py, min_length=8) is enforced by Pydantic
        // before either code path runs, as a 422 the client-side length check makes unreachable
        // in practice. So the three refusals ARE distinguishable by status.
        // AuthFailure.Refused still carries only `statusCode` with no case split, and AuthError
        // (Step 2, given verbatim) has one flat RegistrationRefused — both fixed, unmodifiable
        // interfaces for this task. Adding a status-aware branch here would invent a distinction
        // the given types cannot express, not honor one the server makes. One message covers all.
        is AuthFailure.Refused -> AuthError.RegistrationRefused
        else -> AuthError.Unknown
    }
