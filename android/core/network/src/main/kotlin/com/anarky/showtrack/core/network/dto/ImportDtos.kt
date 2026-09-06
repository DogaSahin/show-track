package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.Serializable

/**
 * `POST /v1/library/import/anilist`'s body. The username is free-form
 * (`backend/app/library/schemas.py`'s `ImportRequest`).
 */
@Serializable
data class ImportAniListRequest(
    val username: String,
)

/**
 * `POST /v1/library/import/anilist`'s response (backend's `ImportSummary`, decision 4-H —
 * synchronous, no task table). Field names already match the wire 1:1, so no `@SerialName` here.
 *
 * [truncated] defaults to `false` on the wire (`backend/app/library/schemas.py`), matched here so
 * an older server that omitted the field entirely (were one ever recorded) would still decode
 * rather than fail — the same defensive default the backend itself carries, mirrored rather than
 * relied upon (decoding never actually sees an absent key against the current server).
 */
@Serializable
data class ImportSummaryDto(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val truncated: Boolean = false,
)
