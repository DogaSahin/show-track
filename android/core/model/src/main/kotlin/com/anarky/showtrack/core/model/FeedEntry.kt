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
 * [mediaId] is the persisted title's own id (round 1 fix): [media] is a [MediaSummary], which
 * deliberately has NO `id` (decision C-N — a search result writes nothing, so no row exists to
 * have one), but the wire's `FeedItem.media` is a `MediaDetail`, a PERSISTED row that genuinely
 * carries one — dropping it in `GroupMapper.toSummary()` left a feed row with no way to open
 * `DetailRoute`. Null exactly when [media] is null (the `imported` kind); never add an `id` to
 * [MediaSummary] itself to avoid this — C-N's reasoning holds for every OTHER caller of
 * [MediaSummary], and a nullable id there would force all of them to handle a case that is
 * actually specific to this one.
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
    val mediaId: String?,
    val payload: Map<String, String>,
    val createdAt: Instant,
) {
    init {
        // Structural enforcement of E-H (see [media]'s own KDoc above): "null exactly when media
        // is null" was, until now, a rule a reader had to remember rather than one the compiler or
        // a constructor could catch — `FeedEntry(media = null, mediaId = "x")` compiled cleanly.
        // Seven later tasks build fakes/`@Preview` fixtures against this type; one built the wrong
        // way would silently render an imported row as tappable, exactly what E-H exists to
        // prevent. A documented invariant has to be remembered into every call site; this check
        // cannot be forgotten.
        require((media == null) == (mediaId == null)) {
            "media and mediaId must both be null or both be non-null (E-H); got media=$media, mediaId=$mediaId"
        }
    }
}
