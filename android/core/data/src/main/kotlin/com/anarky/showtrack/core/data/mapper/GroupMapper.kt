package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.model.WatchlistEntry
import com.anarky.showtrack.core.network.dto.FeedItemDto
import com.anarky.showtrack.core.network.dto.GroupActorDto
import com.anarky.showtrack.core.network.dto.GroupDto
import com.anarky.showtrack.core.network.dto.GroupWithInviteDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MemberDto
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

fun GroupDto.toDomain(): Group = Group(id = id, name = name, createdAt = Instant.parse(createdAt))

fun GroupWithInviteDto.toDomain(): GroupWithInvite =
    GroupWithInvite(
        group = Group(id = id, name = name, createdAt = Instant.parse(createdAt)),
        inviteCode = inviteCode,
        expiresAt = Instant.parse(inviteCodeExpiresAt),
    )

/**
 * `GroupRole` is strict — `valueOf(uppercase())`, the same crash-loud convention `MediaMapper`
 * uses for `MediaSource`/`MediaType`/`MediaStatus`: unlike [ActivityKind] below, there is no
 * behaviour a client could sensibly fall back to for a third role it does not understand.
 */
fun MemberDto.toDomain(): GroupMember =
    GroupMember(
        userId = userId,
        username = username,
        role = GroupRole.valueOf(role.uppercase()),
        joinedAt = Instant.parse(joinedAt),
    )

fun GroupActorDto.toDomain(): GroupActor = GroupActor(id = id, username = username)

/**
 * Drops `id`, `status` and the next-episode block that [MediaDto] itself carries — a feed row or a
 * watchlist entry renders a title CARD, not a detail screen, and reuses the same [MediaSummary]
 * shape search results use rather than minting a third one (task brief: "MediaSummary already
 * exists — reuse it").
 */
fun MediaDto.toSummary(): MediaSummary =
    MediaSummary(
        source = MediaSource.valueOf(source.uppercase()),
        externalId = externalId,
        type = MediaType.valueOf(type.uppercase()),
        title = title,
        year = year,
        genres = genres,
        coverImageUrl = coverImageUrl,
    )

/**
 * A future seventh kind must decode into something rather than crash a client built today — the
 * same treatment [statusOf] in `SearchMapper` gives an unrecognised search-provider key. Every
 * value the backend actually sends (`app/library/models.py ActivityKind`) is upper-cased and
 * matched by name; anything else, including a case mismatch, falls back to [ActivityKind.UNKNOWN].
 */
private fun kindOf(raw: String): ActivityKind =
    ActivityKind.entries.find { it.name.equals(raw, ignoreCase = true) } ?: ActivityKind.UNKNOWN

/**
 * `payload` is `dict[str, Any]` on the wire (spec §6) — an untyped bag is a coupling to interpret
 * here, at the boundary, rather than letting a raw [JsonElement] leak past `:core:data`. A
 * [JsonPrimitive] stringifies to its bare content (no surrounding quotes, even for a JSON string);
 * a nested object or array falls back to its JSON text — both are lossy in the same direction
 * `Map<String, String>` already commits to.
 */
private fun JsonElement.stringify(): String = if (this is JsonPrimitive) content else toString()

/**
 * [FeedItemDto.media] maps to [FeedEntry.media], which stays nullable end to end: an
 * [ActivityKind.IMPORTED] row carries `media: null` on the wire (decision S-A) and MUST render
 * without one — see [FeedEntry]'s own KDoc. Do not default this to a placeholder [MediaSummary].
 */
fun FeedItemDto.toDomain(): FeedEntry =
    FeedEntry(
        id = id,
        actor = actor.toDomain(),
        kind = kindOf(kind),
        media = media?.toSummary(),
        payload = payload.mapValues { (_, value) -> value.stringify() },
        createdAt = Instant.parse(createdAt),
    )

fun WatchlistItemDto.toDomain(): WatchlistEntry =
    WatchlistEntry(
        id = id,
        media = media.toSummary(),
        proposedBy = proposedBy,
        createdAt = Instant.parse(createdAt),
    )

fun ProgressEntryDto.toDomain(): MemberProgress =
    MemberProgress(
        member = member.toDomain(),
        status = UserMediaStatus.valueOf(status.uppercase()),
        progress = progress,
    )
