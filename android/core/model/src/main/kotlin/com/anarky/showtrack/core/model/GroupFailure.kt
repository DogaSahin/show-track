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
     * 404 on a call scoped to ONE row that is not the group itself: `DELETE
     * /v1/groups/{id}/watchlist/{entryId}` (the entry was already removed — any member may remove
     * any entry, so two members racing to delete the same row is a real, not hypothetical, case) or
     * `PATCH /v1/reviews/{id}` (no such review, or it is not this account's).
     *
     * Named to read as distinct from [NotAMember] — but for `DELETE .../watchlist/{entryId}`
     * specifically, this mapping is a chosen approximation, not a sound one: that route depends on
     * `GroupMemberDep` (`backend/app/groups/dependencies.py`'s `require_membership`), which raises
     * its OWN 404 (`_NO_SUCH_GROUP`) when the caller is no longer a member — indistinguishable by
     * status code from `routes.py`'s "no such watchlist entry" 404, since [guarded] maps on HTTP
     * status alone (no response-body parsing). So an owner removing you from the group while your
     * watchlist screen is still open, followed by a tap on remove, surfaces as [NoSuchEntry]
     * ("that entry is already gone") even though the true cause is [NotAMember] ("you were removed
     * from the group"). The mapping stays [NoSuchEntry] anyway: the race this KDoc's first
     * paragraph describes (two members deleting the same row) is far more common than the
     * membership race, and choosing [NotAMember] as the default would misreport THAT one instead.
     * `PATCH /v1/reviews/{id}` has no such dependency and no such ambiguity.
     *
     * This shadow is systemic, not specific to this member: any `guarded(notFound = ...)` override
     * on a route that also takes `GroupMemberDep` inherits it — [NoSuchTitle]'s override on
     * `proposeTitle` (`POST /v1/groups/{id}/watchlist`, same dependency) has the identical
     * ambiguity for the identical reason.
     */
    data object NoSuchEntry : GroupFailure

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

    /**
     * 400 from `POST /v1/groups/join` (fix round 2, task 9c.1): a bad, unknown, or expired invite
     * code — `resolve_invite_code`/`join_group`'s own comment, `backend/app/groups/routes.py`,
     * "one generic message for wrong, unknown AND expired". No payload: the client has no more
     * specific reason to give than the server does, and none of the three sub-causes is
     * distinguishable from the others by status code alone.
     *
     * A dedicated case rather than folding into [Unknown] (round 1's original shape): [Unknown]
     * is ALSO what a 500, an expired session's 401, or a deserialization failure map to, and a UI
     * that rendered "that code might be wrong" for any of those would be actively misleading —
     * measured in review: round 1's `messageRes(unknownRes = …)` could not tell a genuine 400
     * apart from those other causes, since all of them arrived as the same [Unknown] type. Wired
     * through `guarded(badRequest = GroupFailure.BadRequest)` in `GroupRepositoryImpl` — the same
     * caller-chosen-sink shape [NoSuchTitle]/[NoSuchEntry] already use for 404 via `notFound`.
     */
    data object BadRequest : GroupFailure

    /**
     * Anything else — an unexpected status, a malformed response, ...
     *
     * [cause] is for LOGGING ONLY (round 1 fix, stated explicitly): decision C-R keeps
     * `retrofit2`/`okhttp3` types out of every `:feature:*` module's compile classpath, but nothing
     * stops a caller reading `cause.message` at runtime and putting it straight into user-facing
     * copy — an `HttpException`'s message is literally the HTTP status line ("HTTP 500 Internal
     * Server Error"). Render a fixed "something went wrong" string for this case; never `cause`'s
     * own message.
     */
    data class Unknown(
        val cause: Throwable,
    ) : GroupFailure
}
