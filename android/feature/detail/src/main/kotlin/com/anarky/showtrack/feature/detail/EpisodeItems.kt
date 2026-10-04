package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.Season
import com.anarky.showtrack.core.model.UserMediaStatus
import java.time.LocalDate

private val CheckCircleSize = 24.dp
private val EpisodeRowMinHeight = 48.dp
private val SeasonBarHeight = 4.dp
private const val COLLAPSED_ROTATION = -90f

/** What the Episodes section can ask for. */
internal data class EpisodeActions(
    val onToggleEpisode: (Episode) -> Unit = {},
    val onToggleSeason: (Int) -> Unit = {},
    val onMarkSeason: (Int, Boolean) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onAnimeRange: (Int) -> Unit = {},
)

/**
 * The Episodes section as rows of the screen's own list (never a nested list), so a 1,000-episode
 * show scrolls like any other. Anime shows one tile grid; everything else a card per season.
 */
@Suppress("LongParameterList")
internal fun LazyListScope.episodeItems(
    state: EpisodesState,
    anime: Boolean,
    today: LocalDate,
    animeRange: Int?,
    animeColumns: Int,
    actions: EpisodeActions,
) {
    item(key = "episodes-heading") {
        Text(
            text = stringResource(R.string.detail_episodes_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
    when (state) {
        EpisodesState.Loading ->
            item(key = "episodes-loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        EpisodesState.NotAvailable ->
            item(
                key = "episodes-not-loaded",
            ) { EpisodesNote(text = stringResource(R.string.detail_episodes_not_loaded)) }
        EpisodesState.Failed ->
            item(key = "episodes-failed") {
                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    EpisodesNote(text = stringResource(R.string.detail_episodes_error), modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = actions.onRetry,
                    ) { Text(text = stringResource(R.string.detail_episodes_retry)) }
                }
            }
        is EpisodesState.Ready ->
            if (anime) {
                animeItems(state, today, animeRange, animeColumns, actions)
            } else {
                seasonItems(state, today, actions)
            }
    }
}

@Composable
private fun EpisodesNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

private fun LazyListScope.seasonItems(
    state: EpisodesState.Ready,
    today: LocalDate,
    actions: EpisodeActions,
) {
    state.list.seasons.forEach { season ->
        val open = season.number in state.expanded
        item(key = "season-${season.number}") {
            SeasonHeader(season = season, state = state, open = open, actions = actions)
        }
        if (open) {
            items(items = season.episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    state = state,
                    today = today,
                    onToggle = { actions.onToggleEpisode(episode) },
                )
            }
        }
    }
}

@Composable
private fun SeasonHeader(
    season: Season,
    state: EpisodesState.Ready,
    open: Boolean,
    actions: EpisodeActions,
) {
    val total = season.episodes.size
    val watchedCount = season.episodes.count { it.id in state.watched }
    val complete = total > 0 && watchedCount == total
    val openState = stringResource(if (open) R.string.detail_season_expanded else R.string.detail_season_collapsed)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .clickable { actions.onToggleSeason(season.number) }
                        .semantics { stateDescription = openState }
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (open) 0f else COLLAPSED_ROTATION),
                )
                SeasonTitle(season = season, modifier = Modifier.weight(1f))
                if (state.tracking) {
                    SeasonCount(watched = watchedCount, total = total, complete = complete)
                    SeasonMenu(seasonNumber = season.number, onMarkSeason = actions.onMarkSeason)
                } else {
                    Spacer(modifier = Modifier.size(12.dp))
                }
            }
            if (state.tracking && total > 0) {
                LinearProgressIndicator(
                    progress = { watchedCount.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(SeasonBarHeight),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** "Season 2" over "10 episodes · 2025". */
@Composable
private fun SeasonTitle(
    season: Season,
    modifier: Modifier = Modifier,
) {
    val total = season.episodes.size
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.detail_season_title, season.number),
            style = MaterialTheme.typography.titleSmall,
        )
        val year = season.episodes.firstNotNullOfOrNull { it.airDate }?.year
        val count = pluralStringResource(R.plurals.detail_season_episodes, total, total)
        Text(
            text = if (year != null) stringResource(R.string.detail_season_subtitle, count, year) else count,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeasonCount(
    watched: Int,
    total: Int,
    complete: Boolean,
) {
    val color = if (complete) UserMediaStatus.COMPLETED.markColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (complete) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = stringResource(R.string.detail_season_count, watched, total),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun SeasonMenu(
    seasonNumber: Int,
    onMarkSeason: (Int, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.detail_season_menu, seasonNumber),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.detail_mark_season_watched)) },
                onClick = {
                    open = false
                    onMarkSeason(seasonNumber, true)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.detail_mark_season_unwatched)) },
                onClick = {
                    open = false
                    onMarkSeason(seasonNumber, false)
                },
            )
        }
    }
}

/** One episode. The whole row is the tap target; an unaired one is not tappable. */
@Composable
private fun EpisodeRow(
    episode: Episode,
    state: EpisodesState.Ready,
    today: LocalDate,
    onToggle: () -> Unit,
) {
    val watched = episode.id in state.watched
    val title = episode.title ?: stringResource(R.string.detail_episode_fallback_title, episode.number)
    val spoken = episodeDescription(episode, watched, state.tracking, today)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(
                    if (episode.id in
                        state.highlighted
                    ) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = EpisodeRowMinHeight)
                    .clickable(enabled = state.tracking && episode.aired, onClick = onToggle)
                    .clearAndSetSemantics { contentDescription = spoken }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.tracking) WatchCircle(watched = watched, aired = episode.aired)
            Text(
                text = episode.number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val colors = MaterialTheme.colorScheme
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (episode.aired) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AirDateLabel(episode = episode, today = today)
        }
    }
}

@Composable
private fun AirDateLabel(
    episode: Episode,
    today: LocalDate,
) {
    val date = episode.airDate ?: return
    if (episode.aired) {
        Text(
            text = formatAirDate(date, today),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text =
                if (date == today.plusDays(1)) {
                    stringResource(R.string.detail_episode_airs_tomorrow)
                } else {
                    stringResource(R.string.detail_episode_airs, formatAirDate(date, today))
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Watched: filled with a tick. Aired: an empty ring. Not aired yet: a dashed ring. */
@Composable
private fun WatchCircle(
    watched: Boolean,
    aired: Boolean,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    when {
        watched ->
            Box(
                modifier = Modifier.size(CheckCircleSize).clip(CircleShape).background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        aired -> Box(modifier = Modifier.size(CheckCircleSize).border(BorderStroke(2.dp, outline), CircleShape))
        else -> DashedOutline(color = outline, modifier = Modifier.size(CheckCircleSize))
    }
}
