package com.anarky.showtrack.core.model

/**
 * Group/feed/watchlist/review failures translated at the `:core:data` repository boundary
 * (decision C-R): `:feature:groups`/`:feature:feed`/`:feature:detail` cannot see
 * `retrofit2.HttpException`, since `:core:data` depends on `:core:network` with `implementation`
 * scope (architecture rule 2).
 *
 * A plain sealed INTERFACE, not a sealed class extending `Exception` the way [AuthFailure]/
 * [ImportFailure] are — deliberately, per the plan's own interface block. Those two exist only to
 * be thrown; this one is also a plain VALUE later tasks pattern-match on directly (e.g.
 * `GroupsUiState`/`GroupDetailUiState` carrying a `GroupFailure` field to choose copy), and a type
 * that both extends `Exception` and is meant to be compared/held as data pulls in identity
 * semantics (`Exception.equals` is reference equality unless overridden) that a `data class`/
 * `data object` here would have to fight rather than get for free. [GroupOperationException] in
 * `:core:data` is the one carrier that crosses an actual `throw`/`catch` boundary — see its KDoc.
 */
sealed interface GroupFailure {
    /** 404 on a group-scoped read: the group does not exist, or the caller is not a member of it. */
    data object NotAMember : GroupFailure

    /** 403 — a non-owner tried an owner-only action (rotate invite, remove another member). */
    data object NotPermitted : GroupFailure

    /** 404 from `POST /v1/groups/{id}/watchlist`: the proposed `media_id` is not one the server knows. */
    data object NoSuchTitle : GroupFailure

    /**
     * 409 from `POST /v1/reviews`: this account already reviewed the title. [existingReviewId] is
     * always null in practice today — the backend's 409 body carries no id (`ReviewExists` maps to
     * a fixed detail string, `app/library/routes.py`) — but the field stays nullable rather than
     * non-existent so a future backend that starts returning one needs no client-side type change.
     */
    data class AlreadyReviewed(
        val existingReviewId: String?,
    ) : GroupFailure

    /** The request never reached the server. */
    data object Network : GroupFailure

    /** Anything else — an unexpected status, a malformed response, ... */
    data class Unknown(
        val cause: Throwable,
    ) : GroupFailure
}
