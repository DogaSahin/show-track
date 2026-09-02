package com.anarky.showtrack.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CreateGroupRequestDto(
    val name: String,
)

@Serializable
data class JoinGroupRequestDto(
    @SerialName("invite_code") val inviteCode: String,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Returned only to a member — on create, join and rotate. `GET /v1/groups` returns [GroupDto]
 * alone; the invite code is a credential and is not part of the plain group representation
 * (design decision, §1.1 "the invite code is a credential").
 */
@Serializable
data class GroupWithInviteDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("invite_code_expires_at") val inviteCodeExpiresAt: String,
)

@Serializable
data class MemberDto(
    @SerialName("user_id") val userId: String,
    val username: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String,
)

/**
 * `{id, username}` — reused across the feed, member progress and reviews, matching the backend's
 * own `FeedActor` reuse (`app/groups/schemas.py`). See `:core:model`'s `GroupActor` KDoc for why
 * the client does not follow the backend's `library.ReviewAuthor` duplication: that split exists
 * only to dodge a Python import cycle that has no analogue here.
 */
@Serializable
data class GroupActorDto(
    val id: String,
    val username: String,
)

/**
 * `media` is nullable: an `imported` row is about N titles and carries none (decision S-A) — see
 * `:core:model`'s `FeedEntry.media` KDoc. Reuses [MediaDto] rather than a narrower shape because the
 * wire type IS `MediaDetail | null`, the same shape `GET /v1/media/{id}` returns.
 */
@Serializable
data class FeedItemDto(
    val id: String,
    val actor: GroupActorDto,
    val kind: String,
    val media: MediaDto?,
    val payload: Map<String, JsonElement>,
    @SerialName("created_at") val createdAt: String,
)

/** The cursor-paginated list envelope: `{items, next_cursor}` (architecture rule 4). */
@Serializable
data class FeedPageDto(
    val items: List<FeedItemDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class ProposeTitleRequestDto(
    @SerialName("media_id") val mediaId: String,
)

/** `media` is NOT nullable here, unlike [FeedItemDto.media]: a watchlist entry is always about one title. */
@Serializable
data class WatchlistItemDto(
    val id: String,
    val media: MediaDto,
    // Nullable per design doc §5.3: deleting your account leaves the entry standing.
    @SerialName("proposed_by") val proposedBy: String?,
    @SerialName("created_at") val createdAt: String,
)

/** The cursor-paginated list envelope: `{items, next_cursor}` (architecture rule 4). */
@Serializable
data class WatchlistPageDto(
    val items: List<WatchlistItemDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)

/** One member's position on one title — `member` nests [GroupActorDto], matching [FeedItemDto.actor]. */
@Serializable
data class ProgressEntryDto(
    val member: GroupActorDto,
    val status: String,
    val progress: Int,
)
