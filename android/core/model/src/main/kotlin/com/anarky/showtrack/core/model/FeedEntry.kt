package com.anarky.showtrack.core.model

import java.time.Instant

/**
 * One actor, reused across every group-scoped read that attributes a row to a member: the feed,
 * member progress, and reviews. Mirrors the backend's own `FeedActor` reuse inside
 * `app/groups/schemas.py` — the backend duplicates only `library.ReviewAuthor`, and only because a
 * Python import cycle forces it to; nothing analogous constrains this module, so one type serves
 * all three call sites here.
 */
data class GroupActor(
    val id: String,
    val username: String,
)

/**
 * Six wire kinds plus [UNKNOWN], which nothing the backend ever sends maps to today — it exists so
 * a seventh kind a future backend adds decodes into something rather than throwing. `GroupMapper`
 * maps an unrecognised wire string to it, the same treatment `SearchMapper` gives an unknown
 * search-provider key.
 */
enum class ActivityKind { ADDED, IMPORTED, PROGRESSED, RATED, COMPLETED, DROPPED, UNKNOWN }

/**
 * One row of a group's activity feed.
 *
 * [media] is nullable and MUST STAY nullable: an [ActivityKind.IMPORTED] row is about N titles at
 * once (backend decision S-A — one row per imported title would let a single 10,000-entry AniList
 * import bury every other member's activity), so it carries no single title. A feed row must
 * render, and must not be tappable, when [media] is null — see design decision E-H.
 *
 * [payload] is `Map<String, String>`, stringifying whatever the wire sends (`dict[str, Any]` on the
 * backend): an untyped bag is a coupling to interpret at the boundary, in `GroupMapper`, rather than
 * letting a raw `JsonElement` leak past `:core:data` (spec §6).
 */
data class FeedEntry(
    val id: String,
    val actor: GroupActor,
    val kind: ActivityKind,
    val media: MediaSummary?,
    val payload: Map<String, String>,
    val createdAt: Instant,
)
