package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.Episode
import java.time.LocalDate

private val TileCorner = 8.dp

/** The design's 7 columns, or 6 on a phone too narrow for 7 tiles of 48 dp. */
internal const val ANIME_COLUMNS = 7
internal const val ANIME_COLUMNS_NARROW = 6
internal const val ANIME_COLUMNS_MIN_WIDTH_DP = 404
private const val ANIME_RANGE_SIZE = 100

/** The range holding the next episode to watch: where the grid opens. */
internal fun defaultAnimeRange(state: EpisodesState.Ready): Int {
    val next = EpisodeRules.nextToWatch(state.list, state.watched) ?: return 0
    val index =
        state.list.seasons
            .flatMap { it.episodes }
            .indexOfFirst { it.id == next.id }
    return if (index < 0) 0 else index / ANIME_RANGE_SIZE
}

/**
 * Anime: AniList has no episode titles, so one season as numbered tiles, seven a row, split into
 * range tabs past 100 episodes. Watched tiles are filled, the next to watch outlined, the unaired
 * dashed.
 */

internal fun LazyListScope.animeItems(
    state: EpisodesState.Ready,
    today: LocalDate,
    animeRange: Int?,
    columns: Int,
    actions: EpisodeActions,
) {
    val episodes = state.list.seasons.flatMap { it.episodes }
    val ranges = episodes.chunked(ANIME_RANGE_SIZE)
    val next = EpisodeRules.nextToWatch(state.list, state.watched)
    val selected = (animeRange ?: defaultAnimeRange(state)).coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
    val shown = ranges.getOrElse(selected) { emptyList() }
    if (ranges.size > 1) {
        item(key = "anime-ranges") {
            ScrollableTabRow(selectedTabIndex = selected, edgePadding = 16.dp) {
                ranges.forEachIndexed { index, range ->
                    Tab(
                        selected = index == selected,
                        onClick = { actions.onAnimeRange(index) },
                        text = {
                            Text(
                                text =
                                    stringResource(
                                        R.string.detail_episode_range,
                                        range.first().number,
                                        range.last().number,
                                    ),
                            )
                        },
                    )
                }
            }
        }
    }
    items(items = shown.chunked(columns), key = { row -> "anime-row-${row.first().id}" }) { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { episode ->
                EpisodeTile(
                    episode = episode,
                    state = state,
                    isNext = state.tracking && episode.id == next?.id,
                    today = today,
                    onToggle = { actions.onToggleEpisode(episode) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun EpisodeTile(
    episode: Episode,
    state: EpisodesState.Ready,
    isNext: Boolean,
    today: LocalDate,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = state.tracking && episode.id in state.watched
    val spoken =
        episodeDescription(
            episode = episode,
            watched = watched,
            tracking = state.tracking,
            today = today,
        )
    val shape = MaterialTheme.shapes.small
    val primary = MaterialTheme.colorScheme.primary
    val base =
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                when {
                    watched -> primary
                    episode.id in state.highlighted -> MaterialTheme.colorScheme.primaryContainer
                    episode.aired -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
    val outlined =
        when {
            isNext && !watched -> base.border(BorderStroke(1.5.dp, primary), shape)
            else -> base
        }
    Box(
        modifier =
            outlined
                .clickable(enabled = state.tracking && episode.aired, onClick = onToggle)
                .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        if (!episode.aired) {
            DashedOutline(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = TileCorner,
            )
        }
        Text(
            text = episode.number.toString(),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color =
                when {
                    watched -> MaterialTheme.colorScheme.onPrimary
                    episode.aired -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
