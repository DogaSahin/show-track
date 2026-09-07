package com.anarky.showtrack.feature.auth

private const val MIN_PASSWORD_LENGTH = 8

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
): Int? =
    when {
        mode == AuthMode.REGISTER && fields.username.isBlank() -> R.string.auth_validation_username_blank
        '@' !in fields.email -> R.string.auth_validation_email_invalid
        fields.password.length < MIN_PASSWORD_LENGTH -> R.string.auth_validation_password_short
        mode == AuthMode.REGISTER && fields.inviteCode.isBlank() -> R.string.auth_validation_invite_code_blank
        else -> null
    }

/** The one place an [AuthError] becomes copy — see decision C-L in the task brief. */
internal fun AuthError.messageRes(): Int =
    when (this) {
        AuthError.InvalidCredentials -> R.string.auth_error_invalid_credentials
        AuthError.InviteCodeRejected -> R.string.auth_error_invite_code_rejected
        AuthError.EmailOrUsernameTaken -> R.string.auth_error_email_or_username_taken
        AuthError.RegistrationRefused -> R.string.auth_error_registration_refused
        AuthError.AccountCreatedNotSignedIn -> R.string.auth_error_account_created_not_signed_in
        AuthError.Offline -> R.string.auth_error_offline
        AuthError.Unknown -> R.string.auth_error_unknown
    }
