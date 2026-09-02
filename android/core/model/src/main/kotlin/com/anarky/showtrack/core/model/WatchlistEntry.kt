package com.anarky.showtrack.core.model

import java.time.Instant

/**
 * One title proposed to a group's shared watchlist.
 *
 * [proposedBy] is nullable — NOT because the wire ever omits a proposer at creation time, but
 * because deleting your account leaves the entry standing (design doc §5.3): the row survives its
 * proposer. A non-null type here would force every future reader to invent a placeholder identity
 * for a user who no longer exists.
 *
 * [media] is [MediaSummary], not the full [Media] the backend's `MediaDetail` actually carries —
 * see `GroupMapper`'s `MediaDto.toSummary()`: a watchlist row is a proposal, not a library entry,
 * and reuses the same title shape search results use rather than minting a second one.
 */
data class WatchlistEntry(
    val id: String,
    val media: MediaSummary,
    val proposedBy: String?,
    val createdAt: Instant,
)
