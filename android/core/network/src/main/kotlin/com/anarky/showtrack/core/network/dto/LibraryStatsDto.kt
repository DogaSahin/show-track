package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/library/stats`. `byStatus` is keyed by the wire status strings (`"watching"`, ...) —
 * only the statuses that occur are present, never zero-filled (backend's `get_stats` KDoc).
 *
 * `averageScore` is a STRING, not a number — the same reason [LibraryEntryDto.score] is
 * (backend decision 4-N): a JSON number is an IEEE 754 double, and this is a NUMERIC(3,1) average.
 * Null when nothing is rated.
 */
@Serializable
data class LibraryStatsDto(
    val total: Int,
    @SerialName("by_status") val byStatus: Map<String, Int>,
    @SerialName("average_score") val averageScore: String?,
    @SerialName("rated_count") val ratedCount: Int,
)
