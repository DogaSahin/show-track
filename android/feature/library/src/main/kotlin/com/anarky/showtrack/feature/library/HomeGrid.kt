package com.anarky.showtrack.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.PosterGridItem
import com.anarky.showtrack.core.designsystem.component.SkeletonBlock
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.UserMediaStatus

/** Four posters across, on Home and on a single-status view alike. */
internal const val POSTER_COLUMNS = 4

/** Tags each section header, so a test can read the headers in order. */
internal const val SECTION_HEADER_TAG = "library-section-header"

/** The screen's side gutter: the grid's content padding, which the Airing soon row bleeds past. */
internal val GridGutter = 16.dp

internal val PosterGridSpacing = Arrangement.spacedBy(space = 10.dp)
internal val PosterGridRowSpacing = Arrangement.spacedBy(space = 12.dp)
internal val PosterGridPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = 12.dp)

private const val SKELETON_POSTERS = 8
private const val POSTER_ASPECT_RATIO = 2f / 3f
private val DotSize = 8.dp

/**
 * Home's sections into a [POSTER_COLUMNS]-across grid: each section's header, its posters
 * (the first [HOME_COLLAPSED_COUNT] while collapsed, every loaded one expanded), then its
 * "Show all N" / "Show less" button when it holds more than a collapsed section shows.
 *
 * An extension on the grid's scope rather than a grid of its own, so Airing soon above it scrolls
 * with the sections in one list. Item keys carry the section: an entry whose display section moves
 * between two reloads must not collide with its old key while both lists are briefly composed.
 */
internal fun LazyGridScope.homeSections(
    sections: List<HomeSection>,
    onToggleSection: (LibrarySection) -> Unit,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    sections.forEach { section ->
        item(key = "header-${section.section}", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(section = section)
        }
        posters(
            entries = if (section.expanded) section.entries else section.collapsedEntries,
            keyPrefix = section.section.name,
            onEntryClick = onEntryClick,
        )
        if (section.hasMore || section.error != null) {
            item(key = "footer-${section.section}", span = { GridItemSpan(maxLineSpan) }) {
                SectionFooter(section = section, onToggle = { onToggleSection(section.section) })
            }
        }
    }
}

/** One poster per entry, the Caught up pill on those waiting for their next season. */
internal fun LazyGridScope.posters(
    entries: List<LibraryEntry>,
    keyPrefix: String,
    onEntryClick: (LibraryEntry) -> Unit,
) {
    items(items = entries, key = { "$keyPrefix-${it.id}" }) { entry ->
        PosterGridItem(
            title = entry.media.title,
            coverUrl = entry.media.coverImageUrl,
            onClick = { onEntryClick(entry) },
            label = entry.caughtUpLabel(),
        )
    }
}

/**
 * The pill on a Caught up poster: "S3 soon" when a new season has a date, otherwise "More soon".
 * Null for every other entry — a weekly show you are up to date on carries no label; Airing soon
 * covers it.
 *
 * The date check reads [com.anarky.showtrack.core.model.Media.daysUntilNextEpisode] as well: the
 * Room cache keeps the countdown but not the air date, and an offline Home must say the same as an
 * online one. Whether the next episode opens a new season is the server's call (that is what put
 * the entry in Caught up), so a known season here is always the new one.
 */
@Composable
internal fun LibraryEntry.caughtUpLabel(): String? {
    if (section != LibrarySection.CAUGHT_UP) return null
    val season = media.nextEpisodeSeason
    val scheduled = media.nextEpisodeDate != null || media.daysUntilNextEpisode != null
    return if (season != null && scheduled) {
        stringResource(R.string.library_caught_up_season, season)
    } else {
        stringResource(R.string.library_caught_up_more)
    }
}

/** The status a section's header takes its name and colour from. */
private fun LibrarySection.status(): UserMediaStatus =
    when (this) {
        LibrarySection.WATCHING, LibrarySection.CAUGHT_UP -> UserMediaStatus.WATCHING
        LibrarySection.PAUSED -> UserMediaStatus.PAUSED
        LibrarySection.PLANNED -> UserMediaStatus.PLANNED
        LibrarySection.COMPLETED -> UserMediaStatus.COMPLETED
        LibrarySection.DROPPED -> UserMediaStatus.DROPPED
        LibrarySection.UNKNOWN -> UserMediaStatus.UNKNOWN
    }

/** "● Watching 14": the status dot, its name, and the count once the server has reported it. */
@Composable
private fun SectionHeader(section: HomeSection) {
    val status = section.section.status()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(SECTION_HEADER_TAG),
    ) {
        Box(modifier = Modifier.size(DotSize).clip(CircleShape).background(status.markColor()))
        Text(text = status.label(), style = MaterialTheme.typography.titleSmall)
        section.count?.let { count ->
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Under a section: a spinner while its "Show all" fetches, a tap-to-retry note when that failed
 * (tapping asks for "Show all" again), otherwise the button itself.
 */
@Composable
private fun SectionFooter(
    section: HomeSection,
    onToggle: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        when {
            section.loadingMore -> LoadingState()
            section.error != null ->
                Text(
                    text = stringResource(R.string.library_page_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(all = 12.dp),
                )
            else ->
                TextButton(onClick = onToggle) {
                    if (section.expanded) {
                        Text(text = stringResource(R.string.library_show_less))
                    } else {
                        Text(text = stringResource(R.string.library_show_all, section.count ?: section.entries.size))
                        Icon(
                            painter = painterResource(R.drawable.ic_expand_more),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
        }
    }
}

/**
 * What stands in for the grid until the first answer: a header bar (on Home) and two rows of
 * poster shapes, laid out on the same grid so the content lands where the eye already is.
 */
@Composable
internal fun PosterGridSkeleton(
    withHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_COLUMNS),
        userScrollEnabled = false,
        contentPadding = PosterGridPadding,
        horizontalArrangement = PosterGridSpacing,
        verticalArrangement = PosterGridRowSpacing,
        modifier = modifier.fillMaxSize(),
    ) {
        if (withHeader) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SkeletonBlock(modifier = Modifier.padding(top = 12.dp).width(120.dp).height(18.dp))
            }
        }
        items(count = SKELETON_POSTERS) {
            Column(verticalArrangement = Arrangement.spacedBy(space = 4.dp)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT_RATIO))
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(10.dp))
            }
        }
    }
}

/**
 * Lets a full-width grid item (Airing soon) reach past the grid's [gutter] to the screen's edges,
 * so its horizontal row scrolls edge to edge as it did in a plain list. The grid clips to its own
 * bounds, which include the content padding, so nothing is cut off.
 */
internal fun Modifier.bleedPast(gutter: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = (gutter * 2).roundToPx()
        val width = constraints.maxWidth + extra
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter.roundToPx(), 0) }
    }
