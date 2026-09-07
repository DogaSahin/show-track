package com.anarky.showtrack.core.model

import java.time.Instant

/**
 * `owner | member`, matching the backend's `GroupRole` `StrEnum` exactly — parsed with
 * `valueOf(uppercase())` in `GroupMapper`, the same strict (crash-loud on an unrecognised value)
 * convention `MediaMapper` uses for `MediaSource`/`MediaType`/`MediaStatus`. Unlike `ActivityKind`,
 * a third role is not something a client built today has to survive silently — there is no
 * behaviour a client could fall back to for a role it does not understand.
 */
enum class GroupRole { OWNER, MEMBER }

data class GroupMember(
    val userId: String,
    val username: String,
    val role: GroupRole,
    val joinedAt: Instant,
)
