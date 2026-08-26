package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `token_type` is on the wire and deliberately absent here: it is always "bearer", and a field
 * nothing reads is a field that can drift without anyone noticing. `ignoreUnknownKeys` in the
 * shared [kotlinx.serialization.json.Json] is what lets it be omitted.
 */
@Serializable
data class TokenPairDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)
