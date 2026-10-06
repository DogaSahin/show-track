package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlin.math.abs

/** Where one member sits on the race track. */
internal data class RacePin(
    val progress: MemberProgress,
    val isMe: Boolean,
    // Above the line or below it, alternating by position so neighbours never overlap.
    val above: Boolean,
    // How many members on the same episode came before this one: they stack with a small offset.
    val stackIndex: Int,
)

/** How a member stands against you, for the list under the track. */
internal sealed interface Standing {
    data object Finished : Standing

    data object Level : Standing

    data class Ahead(
        val episodes: Int,
    ) : Standing

    data class Behind(
        val episodes: Int,
    ) : Standing
}

/** Pure rules for the group race track, tested alone. */
internal object RaceRules {
    const val TRACK_LIMIT = 6

    /** Everyone, or in a big group the [TRACK_LIMIT] closest to you (you always among them). */
    fun trackMembers(
        members: List<MemberProgress>,
        meId: String?,
    ): List<MemberProgress> {
        if (members.size <= TRACK_LIMIT) return members
        val mine = members.firstOrNull { it.member.id == meId }?.progress ?: return members.take(TRACK_LIMIT)
        return members.sortedWith(compareBy({ it.member.id != meId }, { abs(it.progress - mine) })).take(TRACK_LIMIT)
    }

    fun pins(
        members: List<MemberProgress>,
        meId: String?,
    ): List<RacePin> {
        val seen = mutableMapOf<Int, Int>()
        return members
            .sortedWith(compareBy({ it.progress }, { it.member.username }))
            .mapIndexed { index, member ->
                val stack = seen.getOrDefault(member.progress, 0)
                seen[member.progress] = stack + 1
                RacePin(progress = member, isMe = member.member.id == meId, above = index % 2 == 0, stackIndex = stack)
            }
    }

    /** The line runs to the title's last episode, or to the furthest member while that is unknown. */
    fun trackEnd(
        total: Int?,
        members: List<MemberProgress>,
    ): Int = maxOf(total ?: 0, members.maxOfOrNull { it.progress } ?: 0, 1)

    /** Where each season after the first starts, as an episode count from the start of the track. */
    fun seasonTicks(list: EpisodeList?): List<Int> =
        list
            ?.seasons
            ?.runningFold(0) { acc, season -> acc + season.episodes.size }
            ?.drop(1)
            ?.dropLast(1)
            .orEmpty()

    fun standing(
        member: MemberProgress,
        mine: Int,
    ): Standing =
        when {
            member.status == UserMediaStatus.COMPLETED -> Standing.Finished
            member.progress > mine -> Standing.Ahead(member.progress - mine)
            member.progress < mine -> Standing.Behind(mine - member.progress)
            else -> Standing.Level
        }

    /**
     * The Nth episode in season order as (season, episode), or null when it cannot be told. The
     * season is null for a single-season title (most anime): "Ep 8" there, not "S1 E8".
     */
    fun episodeAt(
        progress: Int,
        list: EpisodeList?,
    ): Pair<Int?, Int>? {
        if (list == null || progress <= 0) return null
        var remaining = progress
        for (season in list.seasons) {
            if (remaining <= season.episodes.size) {
                val number = season.episodes[remaining - 1].number
                return (if (list.seasons.size > 1) season.number else null) to number
            }
            remaining -= season.episodes.size
        }
        return null
    }

    /**
     * The group's list with your own row as this screen knows it: the progress just saved here
     * (the group list itself is refetched only on resume), and no row once you removed the title.
     */
    fun withMyEntry(
        members: List<MemberProgress>,
        meId: String?,
        myProgress: Int?,
        myStatus: UserMediaStatus?,
    ): List<MemberProgress> {
        if (meId == null) return members
        if (myProgress == null || myStatus == null) return members.filterNot { it.member.id == meId }
        return members.map { if (it.member.id == meId) it.copy(progress = myProgress, status = myStatus) else it }
    }
}
