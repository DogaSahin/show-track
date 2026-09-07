package com.anarky.showtrack.core.model

import java.math.BigDecimal

/**
 * `score` has THREE states on the wire — absent ("leave it"), null ("unrate"), a value ("set it")
 * — and a nullable Kotlin property has two. This is the third.
 */
sealed interface ScoreChange {
    data class Set(
        val value: BigDecimal,
    ) : ScoreChange

    data object Clear : ScoreChange
}

/**
 * A partial update. A null field means "not part of this change" — which is safe for status,
 * progress and favorite because those columns are NOT NULL server-side, so `null` could never
 * have been a value to send. Only score needed [ScoreChange].
 */
data class LibraryPatch(
    val status: UserMediaStatus? = null,
    val progress: Int? = null,
    val favorite: Boolean? = null,
    val score: ScoreChange? = null,
)
