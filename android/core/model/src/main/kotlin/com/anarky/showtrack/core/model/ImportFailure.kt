package com.anarky.showtrack.core.model

/**
 * `POST /v1/library/import/anilist` failures translated at the `:core:data` repository boundary —
 * the same discipline [AuthFailure] documents (decision C-R): `:feature:profile` cannot see
 * `retrofit2.HttpException`, since `:core:data` depends on `:core:network` with `implementation`
 * scope (architecture rule 2), so a raw Retrofit exception never reaches a feature module's
 * compile classpath. `Exception` subclasses, not a plain sealed interface, so the existing
 * `throw`/`catch` flow is unchanged.
 */
sealed class ImportFailure(
    cause: Throwable,
) : Exception(cause) {
    /**
     * A 404 from the import endpoint. The server's own `UserListNotAvailable` cannot distinguish
     * "no such AniList user" from "that user's list is private" — both look identical to the
     * backend, which never made the two upstream calls needed to tell them apart (see that
     * exception's docstring) — so this case does not invent a distinction the server does not
     * make either. Copy built from this case must cover both possibilities honestly.
     */
    class ListNotPublic(
        cause: Throwable,
    ) : ImportFailure(cause)

    /** A 422: the username itself was malformed (empty, or over the server's length limit). */
    class InvalidUsername(
        cause: Throwable,
    ) : ImportFailure(cause)

    /** 502/504/429 — the upstream AniList API itself failed, timed out, or rate-limited this server. */
    class UpstreamUnavailable(
        cause: Throwable,
    ) : ImportFailure(cause)

    /** The request never reached the server. */
    class Offline(
        cause: Throwable,
    ) : ImportFailure(cause)

    /** Anything else — an unexpected status, a malformed response, ... */
    class Unexpected(
        cause: Throwable,
    ) : ImportFailure(cause)
}
