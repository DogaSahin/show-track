package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /v1/notifications/targets`.
 *
 * `transport` is a plain String rather than an enum: the backend's `PushTransport` is a
 * VARCHAR + CHECK it can widen in a migration (it just did, to add `unifiedpush`), so a Kotlin
 * enum here would turn a server-side addition into a decode failure on a client that had no
 * reason to care.
 *
 * `target` is nullable and omitted for `ntfy`, where the server mints the topic and REJECTS a
 * supplied one with a 422 (backend 6-L, scoped by decision A-K). For `unifiedpush` it is the
 * endpoint the distributor minted, and it is required.
 */
@Serializable
data class RegisterTargetRequest(
    val transport: String,
    val target: String? = null,
    val label: String? = null,
)

/**
 * The creation response — the ONLY response on this API that carries `target`. The backend's
 * list shape withholds it, because an ntfy topic and a UnifiedPush endpoint are both bearer
 * secrets: whoever has one can post arbitrary notifications to that device.
 *
 * `target` is modelled as nullable rather than required so this same type can decode a future
 * response that stops returning it. Nothing in the client reads it back — we already know the
 * endpoint we sent.
 */
@Serializable
data class PushTargetDto(
    val id: String,
    val transport: String,
    val label: String? = null,
    val target: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

/**
 * The body of a UnifiedPush message, as `UnifiedPushTransport` on the backend writes it: the
 * whole `PushMessage`, JSON-encoded, delivered to the app verbatim.
 *
 * `threshold` is deliberately absent from [com.anarky.showtrack.core.model.PushNotification]
 * — the app does not render it — but it is modelled here anyway so its presence is documented
 * where the contract lives. `ignoreUnknownKeys` would drop it silently either way; naming it
 * makes the wire shape readable from this file alone.
 */
@Serializable
data class PushPayloadDto(
    val title: String,
    val body: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("episode_number") val episodeNumber: Int,
    val threshold: String? = null,
)
