package com.anarky.showtrack.feature.auth

/** Which form the screen shows. Login is the default: it is the shorter, more common path. */
enum class AuthMode { LOGIN, REGISTER }

/**
 * Each case names a different next action (decision C-L). [InviteCodeRejected] and
 * [EmailOrUsernameTaken] split the two refusals `POST /v1/auth/register` actually makes —
 * `backend/app/users/service.py`'s `RegistrationError(400, ...)` for a bad invite code and
 * `RegistrationError(409, ...)` for a taken username/email — because they call for opposite
 * advice: get a working code, versus stop looking for one and sign in instead.
 * [AccountCreatedNotSignedIn] is the same class of mistake one step later: the account EXISTS,
 * so "registration failed" would send the user to retry a form that now answers "email already
 * taken" (decision C-M).
 */
sealed interface AuthError {
    data object InvalidCredentials : AuthError

    /** 400 from register: the invite code itself was rejected. */
    data object InviteCodeRejected : AuthError

    /** 409 from register: get the user to sign in, not to keep trying invite codes. */
    data object EmailOrUsernameTaken : AuthError

    /** Any other 4xx from register — the server defines only the two cases above. */
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

    /**
     * [isNewAccount] (task 9b.6) is what lets `:app` route a fresh registration to the AniList
     * import screen instead of straight to the library, without `:feature:auth` naming
     * `:feature:profile` or `ImportRoute` itself — a `data class` rather than the `data object`
     * this used to be, precisely because login and register both used to produce the SAME value
     * here, which is what made them indistinguishable to `onAuthenticated`'s caller.
     */
    data class Authenticated(
        val isNewAccount: Boolean,
    ) : AuthUiState
}
