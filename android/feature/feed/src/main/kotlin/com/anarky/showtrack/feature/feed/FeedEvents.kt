package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.UserMediaStatus
import java.time.ZoneId

/**
 * One line on the feed: an event, or a run of progress updates folded into one.
 *
 * [entry] is the NEWEST event of the run: its text, time and id are what the row shows, so the
 * row's identity is stable as older events of the same run arrive on later pages. [oldestProgress]
 * is the episode count after the OLDEST event in the run, for the "Ep 13 → 15" chip.
 */
internal data class FeedItem(
    val entry: FeedEntry,
    val oldestProgress: Int? = entry.progressOrNull(),
)

/**
 * Folds runs of progress updates into one line each: consecutive [ActivityKind.PROGRESSED] events
 * (adjacent in this newest-first list) by the same person, on the same show, on the same local day.
 *
 * Display only: the underlying events and paging are untouched. Because it runs over everything
 * paged in so far, a new page that continues the last run is folded into it automatically.
 *
 * Only adjacency counts. A rating between two progress updates splits them into two lines, which
 * keeps the order of what happened honest.
 */
internal fun List<FeedEntry>.mergeProgress(zone: ZoneId): List<FeedItem> {
    val items = mutableListOf<FeedItem>()
    forEach { entry ->
        val last = items.lastOrNull()
        if (last != null && last.entry.continuesWith(entry, zone)) {
            items[items.lastIndex] = last.copy(oldestProgress = entry.progressOrNull())
        } else {
            items += FeedItem(entry)
        }
    }
    return items
}

private fun FeedEntry.continuesWith(
    older: FeedEntry,
    zone: ZoneId,
): Boolean =
    kind == ActivityKind.PROGRESSED &&
        older.kind == ActivityKind.PROGRESSED &&
        mediaId != null &&
        older.mediaId == mediaId &&
        older.actor.id == actor.id &&
        older.createdAt.atZone(zone).toLocalDate() == createdAt.atZone(zone).toLocalDate()

/**
 * What a score change in the payload says. The backend sends the score as a string ("9.5") and a
 * cleared score as JSON null, which the payload map carries as the string "null".
 */
internal sealed interface ScoreChange {
    data class Set(
        val value: String,
    ) : ScoreChange

    data object Cleared : ScoreChange
}

/** The small chip under an event: the numbers behind the sentence. */
internal sealed interface FeedDetail {
    /** "Ep 13 → 15", or "Ep 15" when [from] is null. */
    data class Progress(
        val from: Int?,
        val to: Int,
    ) : FeedDetail

    data class Score(
        val value: String,
        val kind: ActivityKind,
    ) : FeedDetail

    data class DroppedAt(
        val episode: Int,
    ) : FeedDetail

    data class Status(
        val status: UserMediaStatus,
    ) : FeedDetail
}

/**
 * The payload is the change set of the library update behind the event, and every key is optional.
 * Each reader below returns null for a missing key or a value it cannot read, so a bad payload
 * degrades to the plain sentence instead of a chip like "ep 0" or "★ null".
 */
internal fun FeedEntry.progressOrNull(): Int? = payload["progress"]?.toIntOrNull()?.takeIf { it > 0 }

internal fun FeedEntry.scoreChange(): ScoreChange? =
    when (val raw = payload["score"]) {
        null -> null
        "null" -> ScoreChange.Cleared
        else -> raw.toBigDecimalOrNull()?.let { ScoreChange.Set(raw) }
    }

internal fun FeedEntry.statusOrNull(): UserMediaStatus? =
    payload["status"]?.let { raw -> UserMediaStatus.entries.find { it.name.equals(raw, ignoreCase = true) } }

internal fun FeedItem.detail(): FeedDetail? =
    when (entry.kind) {
        ActivityKind.PROGRESSED ->
            entry.progressOrNull()?.let { to ->
                FeedDetail.Progress(from = oldestProgress?.takeIf { it != to }, to = to)
            }
        ActivityKind.RATED, ActivityKind.COMPLETED ->
            (entry.scoreChange() as? ScoreChange.Set)?.let { FeedDetail.Score(value = it.value, kind = entry.kind) }
        ActivityKind.DROPPED -> entry.progressOrNull()?.let { FeedDetail.DroppedAt(episode = it) }
        ActivityKind.ADDED -> entry.statusOrNull()?.let { FeedDetail.Status(status = it) }
        ActivityKind.IMPORTED, ActivityKind.UNKNOWN -> null
    }
