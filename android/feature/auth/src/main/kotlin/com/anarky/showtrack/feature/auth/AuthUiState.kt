package com.anarky.showtrack.feature.auth

/** Which form the screen shows. Login is the default: it is the shorter, more common path. */
enum class AuthMode { LOGIN, REGISTER }

/**
 * Three causes, three different pieces of advice (decision C-L). [AccountCreatedNotSignedIn] is
 * the one that matters most: the account EXISTS, so "registration failed" would send the user to
 * retry a form that now answers "email already taken" (decision C-M).
 */
sealed interface AuthError {
    data object InvalidCredentials : AuthError

    data object RegistrationRefused : AuthError

    data object AccountCreatedNotSignedIn : AuthError

    data object Offline : AuthError

    data object Unknown : AuthError
}

sealed interface AuthUiState {
    data class Form(
        val mode: AuthMode = AuthMode.LOGIN,
        val submitting: Boolean = false,
        val error: AuthError? = null,
    ) : AuthUiState

    data object Authenticated : AuthUiState
}
