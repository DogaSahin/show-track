package com.anarky.showtrack.core.model

import java.time.Instant

/** A group member's review of a title — `POST /v1/reviews` / `PATCH /v1/reviews/{id}`. */
data class Review(
    val id: String,
    val author: GroupActor,
    val mediaId: String,
    val body: String,
    val containsSpoilers: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
