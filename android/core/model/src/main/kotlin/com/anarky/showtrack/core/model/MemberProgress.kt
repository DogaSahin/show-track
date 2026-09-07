package com.anarky.showtrack.core.model

/** One member's position on one title — `GET /v1/groups/{id}/media/{mediaId}/progress`. */
data class MemberProgress(
    val member: GroupActor,
    val status: UserMediaStatus,
    val progress: Int,
)
