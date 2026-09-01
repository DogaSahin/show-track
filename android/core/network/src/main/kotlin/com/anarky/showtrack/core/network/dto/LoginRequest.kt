package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request bodies for the two token-minting endpoints. */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)
