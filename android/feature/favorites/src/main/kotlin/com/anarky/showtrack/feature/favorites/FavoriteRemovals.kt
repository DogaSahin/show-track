package com.anarky.showtrack.feature.favorites

/**
 * Which removed favourites a freshly fetched page must still hide.
 *
 * A removal takes the poster off screen at once, before the server has answered. A page fetched
 * while that request was in flight, or before it, still lists the title, and showing it would make
 * the poster jump back. So each removal is remembered with the refresh generation in which the
 * server confirmed it (null while it is in flight):
 * - a page from a refresh started at or before that generation is stale about it, so it is hidden;
 * - a page from a refresh started after it is the server's truth, so nothing is hidden. That is
 *   what lets a favourite you removed here and then re-added on show details come back.
 *
 * Not thread-safe on purpose: both ViewModels touch it only from `viewModelScope` (main thread).
 */
internal class FavoriteRemovals {
    private val removals = mutableMapOf<String, Int?>()

    /** False when this entry is already being removed, so a double tap sends one request. */
    fun begin(entryId: String): Boolean {
        if (entryId in removals) return false
        removals[entryId] = null
        return true
    }

    fun confirm(
        entryId: String,
        generation: Int,
    ) {
        if (entryId in removals) removals[entryId] = generation
    }

    /** The removal failed or was undone: the title is a favourite again. */
    fun forget(entryId: String) {
        removals.remove(entryId)
    }

    fun hides(
        entryId: String,
        generation: Int,
    ): Boolean {
        if (entryId !in removals) return false
        val confirmedAt = removals[entryId] ?: return true
        return confirmedAt >= generation
    }

    /** After a refresh of [generation] lands, removals it already reflects need no more hiding. */
    fun settle(generation: Int) {
        removals.entries.removeAll { (_, confirmedAt) -> confirmedAt != null && confirmedAt < generation }
    }
}
