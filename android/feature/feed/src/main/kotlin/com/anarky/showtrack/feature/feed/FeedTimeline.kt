package com.anarky.showtrack.feature.feed

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.anarky.showtrack.core.designsystem.component.MediaCover
import com.anarky.showtrack.core.designsystem.component.UserAvatar
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val AvatarSize = 32.dp
private val CoverWidth = 36.dp

/**
 * One row of the rendered timeline: either a day heading or an item beneath it.
 *
 * A flat, pre-computed list rather than a nested `Map<LocalDate, List<FeedItem>>` because
 * `LazyColumn` wants a flat index space — a nested structure would have to be flattened at every
 * `items` call anyway, and doing it once here keeps the day boundaries out of the rendering code.
 */
internal sealed interface FeedRow {
    data class Day(
        val date: LocalDate,
    ) : FeedRow

    data class Entry(
        val item: FeedItem,
    ) : FeedRow
}

/**
 * Folds progress runs ([mergeProgress]) and splits the result into day-headed sections.
 *
 * [zone] is a parameter rather than `ZoneId.systemDefault()` read inside, so a test can pin the
 * boundary. Which day an entry falls on is a LOCAL question — an event at 23:30 UTC is tomorrow for
 * a reader in Tokyo — and grouping in UTC would put the header in the wrong place for most of the
 * world for part of every day.
 *
 * Assumes the server's newest-first ordering rather than sorting: `CursorPaginator` pages a
 * descending stream, and re-sorting here would quietly paper over a backend that had stopped
 * ordering, turning a contract break into a rendering quirk nobody notices.
 */
internal fun List<FeedEntry>.toTimeline(zone: ZoneId): List<FeedRow> {
    val rows = mutableListOf<FeedRow>()
    var currentDay: LocalDate? = null
    mergeProgress(zone).forEach { item ->
        val day =
            item.entry.createdAt
                .atZone(zone)
                .toLocalDate()
        if (day != currentDay) {
            rows += FeedRow.Day(day)
            currentDay = day
        }
        rows += FeedRow.Entry(item)
    }
    return rows
}

/** "TODAY", "YESTERDAY", then "MON 28 SEP". */
@Composable
internal fun FeedDayHeader(
    date: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    Text(
        text = date.headingText(today = today).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 0.04.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * "Today"/"Yesterday" for the two days a reader can place without reading a date, and a short
 * weekday-and-date for everything older, in the order the reader's locale writes it.
 */
@Composable
private fun LocalDate.headingText(today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (this) {
        today -> stringResource(R.string.feed_day_today)
        today.minusDays(1) -> stringResource(R.string.feed_day_yesterday)
        else -> format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale))
    }
}

/**
 * One event: who, what exactly happened, the numbers behind it, when, and the show's cover.
 *
 * Tappable if and only if [FeedEntry.mediaId] is non-null: `Modifier.clickable` is ADDED rather
 * than made conditional, so a row with no title to open registers no click action at all — a
 * screen reader does not offer one and `performClick()` in a test cannot silently succeed against
 * a no-op.
 *
 * The row is ONE spoken sentence ("deniz is 15 episodes into Frieren, episodes 13 to 15, 1 hour
 * ago"): the chip's spoken form is words, where its visual form is "Ep 13 → 15".
 */
@Composable
internal fun FeedEventRow(
    item: FeedItem,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = item.entry
    val sentence = entry.sentence(item)
    val detail = item.detail()
    val time = entry.createdAt.timeLabel(isToday = isToday)
    val spoken = listOfNotNull(sentence.text, detail?.spokenText(), time).joinToString(separator = ", ")
    val clickModifier = if (entry.mediaId != null) modifier.clickable(onClick = onClick) else modifier

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(space = 12.dp),
        modifier =
            clickModifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        UserAvatar(userId = entry.actor.id, name = entry.actor.username, size = AvatarSize)
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let { DetailChip(detail = it) }
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.media?.let { media ->
            MediaCover(coverImageUrl = media.coverImageUrl, modifier = Modifier.width(CoverWidth))
        }
    }
}

/**
 * "**deniz** is 15 episodes into **Frieren**": the person and the title emphasised, the verb not.
 *
 * The actor is a separate span from the action, a deliberate localisation compromise (it fixes the
 * subject ahead of the verb) made so a reader scanning the column sees WHO at a glance. The title
 * is found inside the formatted action rather than split into its own string, so a translator
 * still gets the whole verb phrase in one piece.
 */
@Composable
private fun FeedEntry.sentence(item: FeedItem) =
    buildAnnotatedString {
        val emphasis = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        withStyle(emphasis) { append(actor.username) }
        append(" ")
        val action = actionText(item)
        val title = media?.title
        val start = if (title.isNullOrEmpty()) -1 else action.indexOf(title)
        if (title == null || start < 0) {
            append(action)
        } else {
            append(action.substring(0, start))
            withStyle(emphasis) { append(title) }
            append(action.substring(start + title.length))
        }
    }

/**
 * `media?.title` rather than a non-null assertion for the five kinds the backend always pairs with
 * a title: [FeedEntry]'s `init` guarantees `media`/`mediaId` are null TOGETHER, not that only
 * IMPORTED can be the null one.
 */
@Composable
private fun FeedEntry.actionText(item: FeedItem): String {
    val title = media?.title ?: stringResource(R.string.feed_unknown_title)
    return when (kind) {
        ActivityKind.ADDED -> stringResource(R.string.feed_action_added, title)
        ActivityKind.PROGRESSED ->
            item.entry.progressOrNull()?.let { progress ->
                pluralStringResource(R.plurals.feed_action_progressed_count, progress, progress, title)
            } ?: stringResource(R.string.feed_action_progressed, title)
        ActivityKind.RATED ->
            if (scoreChange() == ScoreChange.Cleared) {
                stringResource(R.string.feed_action_unrated, title)
            } else {
                stringResource(R.string.feed_action_rated, title)
            }
        ActivityKind.COMPLETED -> stringResource(R.string.feed_action_completed, title)
        ActivityKind.DROPPED -> stringResource(R.string.feed_action_dropped, title)
        ActivityKind.IMPORTED -> {
            val count = payload["count"] ?: stringResource(R.string.feed_import_count_unknown)
            stringResource(R.string.feed_action_imported, count)
        }
        ActivityKind.UNKNOWN -> stringResource(R.string.feed_action_unknown)
    }
}

/**
 * A relative span under TODAY's heading, a clock time under every other one.
 *
 * A day heading already locates the entry, so under "Yesterday" a relative span would render the
 * literal word "Yesterday" a second time, and under a dated heading "2 days ago" merely restates
 * the date above it. A clock time adds the one thing the heading cannot carry. Today is the
 * exception: "2 hours ago" is easier to place than "14:32" when the heading only says "Today".
 *
 * Neither form ticks. Both are computed at composition — the right trade for a feed the user
 * refreshes; a per-row clock would recompose the visible list on a timer for text nobody watches.
 */
@Composable
private fun Instant.timeLabel(isToday: Boolean): String =
    if (isToday) {
        DateUtils
            .getRelativeTimeSpanString(
                toEpochMilli(),
                Instant.now().toEpochMilli(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
    } else {
        atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
