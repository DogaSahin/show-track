package com.anarky.showtrack.core.model

import java.math.BigDecimal
import java.time.Instant

data class LibraryEntry(
    val id: String,
    val status: UserMediaStatus,
    // BigDecimal, NEVER Double. The API serialises score as a JSON STRING ("8.5") precisely
    // because a JSON number is an IEEE 754 double, and routing it through one reintroduces the
    // drift NUMERIC(3,1) exists to prevent (backend decision 4-N).
    val score: BigDecimal?,
    val progress: Int,
    val favorite: Boolean,
    val updatedAt: Instant,
    val media: Media,
)
