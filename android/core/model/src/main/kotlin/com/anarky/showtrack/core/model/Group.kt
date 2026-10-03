package com.anarky.showtrack.core.model

import java.time.Instant

/**
 * A closed group of members sharing a feed, a watchlist and progress comparison (task 9c.0).
 *
 * The summary fields come from `GET /v1/groups` only, so they default to "unknown" for a group
 * that came back from create, join or rotate: [myRole] null (an unrecognised role decodes to null
 * too), the counts null, the previews empty. A screen must treat null as "not known yet", never
 * as zero or as "not the owner".
 */
data class Group(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val myRole: GroupRole? = null,
    val memberCount: Int? = null,
    /** The owner first, then by join date; at most four. */
    val memberPreview: List<GroupActor> = emptyList(),
    val watchlistCount: Int? = null,
    /** The newest watchlist titles first; at most four. */
    val watchlistPreview: List<WatchlistCover> = emptyList(),
)

/** One poster in a group card's watchlist preview. */
data class WatchlistCover(
    val mediaId: String,
    val coverImageUrl: String?,
)
