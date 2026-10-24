package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Season

/** The Episodes section. Separate from the title's own load, so a slow list never blocks the page. */
sealed interface EpisodesState {
    data object Loading : EpisodesState

    /** The server has not fetched this title's list yet (`synced_at: null`). */
    data object NotAvailable : EpisodesState

    data object Failed : EpisodesState

    /**
     * [tracking] is false when the title is not in the library: the seasons show, without circles
     * or counts. [highlighted] are the rows a catch-up prompt offers to mark, tinted while it shows.
     */
    data class Ready(
        val list: EpisodeList,
        val watched: Set<String>,
        val tracking: Boolean,
        val expanded: Set<Int>,
        val catchUp: CatchUp? = null,
    ) : EpisodesState {
        val highlighted: Set<String> get() = catchUp?.episodeIds.orEmpty()
    }
}

/** "Also mark E4–E5 as watched?": aired, unwatched episodes before the one just marked. */
data class CatchUp(
    val season: Int,
    val fromNumber: Int,
    val toNumber: Int,
    val episodeIds: Set<String>,
)

/** Pure rules for the Episodes section, kept apart from the ViewModel so they are tested alone. */
internal object EpisodeRules {
    /** Aired, unwatched episodes before [episode] in its own season; null when there are none. */
    fun catchUpFor(
        list: EpisodeList,
        watched: Set<String>,
        episode: Episode,
    ): CatchUp? {
        val season = list.seasons.firstOrNull { season -> season.episodes.any { it.id == episode.id } } ?: return null
        val before =
            season.episodes.filter { it.number < episode.number && it.aired && it.id !in watched }
        if (before.isEmpty()) return null
        return CatchUp(
            season = season.number,
            fromNumber = before.minOf { it.number },
            toNumber = before.maxOf { it.number },
            episodeIds = before.mapTo(mutableSetOf()) { it.id },
        )
    }

    /**
     * The season opened by default: the one holding the first aired, unwatched episode. When there
     * is none (all watched, or not tracking), the first season with anything still to air.
     */
    fun defaultExpanded(
        list: EpisodeList,
        watched: Set<String>,
        tracking: Boolean,
    ): Set<Int> {
        val next = if (tracking) nextToWatch(list, watched) else null
        val season =
            next?.let { seasonOf(list, it)?.number }
                ?: list.seasons.firstOrNull { season -> season.episodes.any { !it.aired } }?.number
        return setOfNotNull(season)
    }

    /** The first aired episode not yet watched, in season and episode order. */
    fun nextToWatch(
        list: EpisodeList,
        watched: Set<String>,
    ): Episode? =
        list.seasons
            .asSequence()
            .flatMap { it.episodes }
            .firstOrNull { it.aired && it.id !in watched }

    /** What "Mark season watched / unwatched" acts on: only aired episodes. */
    fun airedIds(season: Season): Set<String> = season.episodes.filter { it.aired }.mapTo(mutableSetOf()) { it.id }

    private fun seasonOf(
        list: EpisodeList,
        episode: Episode,
    ): Season? = list.seasons.firstOrNull { season -> season.episodes.any { it.id == episode.id } }
}
