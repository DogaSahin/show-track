package com.anarky.showtrack.core.model

/**
 * Auth failures translated at the `:core:data` repository boundary.
 *
 * `:feature:auth` cannot see `retrofit2.HttpException` — `:core:data` depends on `:core:network`
 * with `implementation` scope (architecture rule 2), so a raw Retrofit exception never reaches a
 * feature module's compile classpath. Without this translation `:feature:auth` could only catch
 * generic `Exception` and could not tell a wrong password from being offline, on the one screen
 * where that distinction is the whole user experience. `Exception` subclasses, not a plain sealed
 * interface, so the existing `throw`/`catch` flow is unchanged.
 */
sealed class AuthFailure(
    cause: Throwable,
) : Exception(cause) {
    /** A 401 from `POST /v1/auth/login`: the email/password pair was wrong. */
    class InvalidCredentials(
        cause: Throwable,
    ) : AuthFailure(cause)

    /**
     * A 4xx from `POST /v1/auth/register`. [statusCode] only, deliberately: the backend does not
     * let the client tell a bad invite code from a taken email apart from the status alone, so
     * this does not invent a distinction the server itself doesn't make.
     */
    class Refused(
        val statusCode: Int,
        cause: Throwable,
    ) : AuthFailure(cause)

    /** The request never reached the server. */
    class Offline(
        cause: Throwable,
    ) : AuthFailure(cause)

    /** Anything else — an unexpected status, a malformed response, ... */
    class Unexpected(
        cause: Throwable,
    ) : AuthFailure(cause)
}
