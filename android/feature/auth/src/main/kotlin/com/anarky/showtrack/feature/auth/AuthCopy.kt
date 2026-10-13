package com.anarky.showtrack.feature.auth

// The server's own bounds (backend/app/users/schemas.py), mirrored so a request it would refuse
// is never sent and the user gets a specific message instead of a generic error.
private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_LENGTH = 256
private const val MAX_USERNAME_LENGTH = 64
private const val MAX_INVITE_CODE_LENGTH = 64

// Loose on purpose: it only has to reject what the server's email check certainly rejects — no
// dot in the domain, whitespace, a missing @. The server stays the authority.
private val EMAIL_SHAPE = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

// The server counts Unicode code points, not UTF-16 units, so an emoji is one character here too.
private fun String.characterCount(): Int = codePointCount(0, length)

/**
 * [AuthError.EmailOrUsernameTaken] and [AuthError.AccountCreatedNotSignedIn] only — both are
 * refusals that only signing in can fix, so the message shown for them offers a "Log in" action
 * instead of leaving the user to find the mode switch at the foot of the screen.
 */
internal val AuthError.pointsAtSigningIn: Boolean
    get() = this == AuthError.EmailOrUsernameTaken || this == AuthError.AccountCreatedNotSignedIn

/**
 * Avoids a pointless round trip the server would refuse anyway (decision, task brief). Never a
 * replacement for handling [AuthError] below — the server stays the authority on whether an
 * invite code or an email is actually valid.
 */
internal fun validate(
    mode: AuthMode,
    fields: AuthFormFields,
): Int? {
    val registering = mode == AuthMode.REGISTER
    return when {
        registering && fields.username.isBlank() -> R.string.auth_validation_username_blank
        registering && fields.username.trim().characterCount() > MAX_USERNAME_LENGTH ->
            R.string.auth_validation_username_long
        !EMAIL_SHAPE.matches(fields.email.trim()) -> R.string.auth_validation_email_invalid
        fields.password.characterCount() < MIN_PASSWORD_LENGTH -> R.string.auth_validation_password_short
        fields.password.characterCount() > MAX_PASSWORD_LENGTH -> R.string.auth_validation_password_long
        registering && fields.inviteCode.isBlank() -> R.string.auth_validation_invite_code_blank
        registering && fields.inviteCode.characterCount() > MAX_INVITE_CODE_LENGTH ->
            R.string.auth_validation_invite_code_long
        else -> null
    }
}

/** The one place an [AuthError] becomes copy — see decision C-L in the task brief. */
internal fun AuthError.messageRes(): Int =
    when (this) {
        AuthError.InvalidCredentials -> R.string.auth_error_invalid_credentials
        AuthError.InviteCodeRejected -> R.string.auth_error_invite_code_rejected
        AuthError.EmailOrUsernameTaken -> R.string.auth_error_email_or_username_taken
        AuthError.RegistrationRefused -> R.string.auth_error_registration_refused
        AuthError.AccountCreatedNotSignedIn -> R.string.auth_error_account_created_not_signed_in
        AuthError.TooManyAttempts -> R.string.auth_error_too_many_attempts
        AuthError.ServerBusy -> R.string.auth_error_server_busy
        AuthError.Offline -> R.string.auth_error_offline
        AuthError.Unknown -> R.string.auth_error_unknown
    }
