package com.anarky.showtrack.core.model

import java.time.Instant

/** The signed-in account, as `GET /v1/users/me` describes it: what the Profile header shows. */
data class CurrentUser(
    val id: String,
    val username: String,
    val email: String,
    val createdAt: Instant,
)
