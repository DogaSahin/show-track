package com.anarky.showtrack.core.model

/**
 * One episode-airing push, already parsed out of the bytes the distributor delivered.
 *
 * A `:core:model` type rather than the wire DTO, for the reason every other domain model here
 * exists: `:feature:profile` renders this and must not be able to name a `kotlinx.serialization`
 * shape from `:core:network` (architecture rule 2). The decode happens inside `:core:data`.
 *
 * [mediaId] is the field the whole UnifiedPush transport exists for. ntfy's own title/message
 * format has nowhere to put it, so a notification delivered that way can only open the app;
 * carrying it lets the tap open the title the notification is ABOUT (decision A-A, backend 6-O).
 */
data class PushNotification(
    val title: String,
    val body: String,
    val mediaId: String,
    val episodeNumber: Int,
)
