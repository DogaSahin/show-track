package com.anarky.showtrack.core.model

import java.time.Instant

/**
 * `owner | member`, matching the backend's `GroupRole` `StrEnum`. [UNKNOWN] stands in for a role
 * this build does not recognise: it is displayed as a plain member and never grants owner rights.
 */
enum class GroupRole { OWNER, MEMBER, UNKNOWN }

data class GroupMember(
    val userId: String,
    val username: String,
    val role: GroupRole,
    val joinedAt: Instant,
)
