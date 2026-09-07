package com.anarky.showtrack.feature.feed

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val GutterWidth = 28.dp
private val DotSize = 9.dp
private val RuleWidth = 2.dp

/**
 * One row of the rendered timeline: either a day heading or an entry beneath it.
 *
 * A flat, pre-computed list rather than a nested `Map<LocalDate, List<FeedEntry>>` because
 * `LazyColumn` wants a flat index space — a nested structure would have to be flattened at every
 * `items` call anyway, and doing it once here keeps the day boundaries out of the rendering code.
 */
internal sealed interface FeedRow {
    data class Day(
        val date: LocalDate,
    ) : FeedRow

    data class Entry(
        val entry: FeedEntry,
    ) : FeedRow
}

/**
 * Splits a paged, newest-first feed into day-headed sections.
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
    forEach { entry ->
        val day = entry.createdAt.atZone(zone).toLocalDate()
        if (day != currentDay) {
            rows += FeedRow.Day(day)
            currentDay = day
        }
        rows += FeedRow.Entry(entry)
    }
    return rows
}

/**
 * A day heading. Indented to the same gutter the entries use, so the rule below it reads as
 * starting under the heading rather than beside it.
 */
@Composable
internal fun FeedDayHeader(
    date: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    Text(
        text = date.headingText(today = today),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = GutterWidth, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * "Today"/"Yesterday" for the two days a reader can place without reading a date, and a localised
 * date for everything older. Anything beyond yesterday gets the real date rather than "3 days ago":
 * a relative span is easy to read and hard to *locate*, and a day heading's job is locating.
 */
@Composable
private fun LocalDate.headingText(today: LocalDate): String =
    when (this) {
        today -> stringResource(R.string.feed_day_today)
        today.minusDays(1) -> stringResource(R.string.feed_day_yesterday)
        else -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

/**
 * One entry on the timeline.
 *
 * The row is `IntrinsicSize.Min` so the gutter's rule can `fillMaxHeight` — inside a `LazyColumn` a
 * `Row`'s children are measured against unbounded height, and `fillMaxHeight` there resolves to
 * nothing. Measuring the row's intrinsic minimum first gives the rule a real height to fill.
 *
 * The rule is drawn for EVERY entry including the last of a day, deliberately: it runs into the
 * gap and the next day's heading picks it up, which is what makes the column read as one continuous
 * timeline rather than as a stack of separate day cards.
 *
 * Tappable if and only if [FeedEntry.mediaId] is non-null, unchanged from the card version and for
 * the unchanged reason: `Modifier.clickable` is ADDED rather than made conditional, so a row with
 * no title to open registers no clickable semantics node at all — a screen reader does not announce
 * it and `performClick()` in a test cannot silently succeed against a no-op.
 */
@Composable
internal fun FeedTimelineRow(
    entry: FeedEntry,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier =
        if (entry.mediaId != null) modifier.clickable(onClick = onClick) else modifier
    Row(modifier = rowModifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        TimelineGutter(kind = entry.kind)
        Column(
            modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(space = 3.dp),
        ) {
            Text(
                text = entry.actorAndAction(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.createdAt.timeLabel(isToday = isToday),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * The rule and the dot. The dot sits on a [MaterialTheme.colorScheme.background] ring so the rule
 * appears to pass behind it rather than through it — the ring is what turns a line with a blob on
 * it into a marked point.
 */
@Composable
private fun TimelineGutter(kind: ActivityKind) {
    Box(modifier = Modifier.width(GutterWidth).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier =
                Modifier
                    .width(RuleWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier =
                Modifier
                    .padding(top = 5.dp)
                    .size(DotSize + 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(DotSize).clip(CircleShape).background(kind.dotColor()))
        }
    }
}

/**
 * The kind's own colour, chosen to line up with [com.anarky.showtrack.core.model.UserMediaStatus]'s
 * marks where the two mean the same thing: a COMPLETED activity is the same teal as a COMPLETED
 * library row, a DROPPED one the same red. ADDED and PROGRESSED take the two active hues; IMPORTED
 * and UNKNOWN stay neutral, because neither is an opinion about a title.
 *
 * Local to this module rather than shared out of `:core:designsystem`: the feed is the only screen
 * that renders an [ActivityKind] at all, and a shared mapping with one consumer is generality
 * bought before it is needed. It moves the moment a second screen wants it.
 */
@Composable
private fun ActivityKind.dotColor(): Color =
    when (this) {
        ActivityKind.ADDED -> MaterialTheme.colorScheme.secondary
        ActivityKind.PROGRESSED -> MaterialTheme.colorScheme.primary
        ActivityKind.RATED -> MaterialTheme.colorScheme.primary
        ActivityKind.COMPLETED -> MaterialTheme.colorScheme.tertiary
        ActivityKind.DROPPED -> MaterialTheme.colorScheme.error
        ActivityKind.IMPORTED, ActivityKind.UNKNOWN -> MaterialTheme.colorScheme.outline
    }

/**
 * "**dogasahin** rated Frieren" — the actor emphasised, the action not.
 *
 * The actor is a separate string from the action, where the card version had one sentence per kind
 * with `%1$s` for the actor. That split is a real localisation compromise — it fixes the subject
 * ahead of the verb — and it is made deliberately: the whole point of the timeline is that a reader
 * scanning it sees WHO at a glance, which needs the actor styled differently from the rest, and
 * that cannot be done inside a single formatted string without matching the actor's name back out
 * of the result (which breaks the moment a username also appears in a title).
 *
 * The verb and its object stay together in one string, so only the subject is separated.
 *
 * `entry.media?.title` rather than a non-null assertion for the five kinds the backend always pairs
 * with a title: [FeedEntry]'s `init` guarantees `media`/`mediaId` are null TOGETHER, not that only
 * IMPORTED can be the null one — nothing in the type system ties "null media" to a specific kind.
 */
@Composable
private fun FeedEntry.actorAndAction() =
    buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            ),
        ) {
            append(actor.username)
        }
        append(" ")
        append(actionText())
    }

@Composable
private fun FeedEntry.actionText(): String {
    val title = media?.title ?: stringResource(R.string.feed_unknown_title)
    return when (kind) {
        ActivityKind.ADDED -> stringResource(R.string.feed_action_added, title)
        ActivityKind.PROGRESSED -> stringResource(R.string.feed_action_progressed, title)
        ActivityKind.RATED -> stringResource(R.string.feed_action_rated, title)
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
 * The split exists because the two halves were saying the same thing. A day heading already
 * locates the entry, so under "Yesterday" a relative span renders the literal word "Yesterday" a
 * second line down — `DateUtils` returns exactly that for anything 24-48 hours old — and under a
 * dated heading "2 days ago" merely restates the date above it. A clock time adds the one thing
 * the heading cannot carry.
 *
 * Today is the exception worth making: "2 hours ago" is genuinely easier to place than "14:32"
 * when the heading is only ever going to say "Today".
 *
 * `DateUtils.getRelativeTimeSpanString` rather than hand-rolled bucketing of elapsed millis: it is
 * localised, knows every plural rule, and is what the rest of the platform uses — so "2 hours ago"
 * reads the same here as in every other app on the device.
 *
 * Neither form ticks. Both are computed at composition, so a row that says "2 minutes ago" still
 * says so an hour later unless something recomposes it. That is the right trade for a feed the
 * user refreshes — a per-row clock would recompose the whole visible list on a timer to update
 * text nobody is watching.
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
