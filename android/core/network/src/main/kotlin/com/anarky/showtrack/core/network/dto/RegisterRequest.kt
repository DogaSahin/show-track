package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /v1/auth/register`. [inviteCode] is REQUIRED and has two server-side meanings: the
 * deployment's registration code, or any group's invite code — the second also joins that group.
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    @SerialName("invite_code") val inviteCode: String,
)

/** The register response. NOT a token pair — registration does not log you in (decision C-M). */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
)
