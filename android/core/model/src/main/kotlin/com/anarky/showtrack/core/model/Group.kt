package com.anarky.showtrack.core.model

import java.time.Instant

/** A closed group of members sharing a feed, a watchlist and progress comparison (task 9c.0). */
data class Group(
    val id: String,
    val name: String,
    val createdAt: Instant,
)
