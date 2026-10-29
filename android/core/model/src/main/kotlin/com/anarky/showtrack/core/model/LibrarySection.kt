package com.anarky.showtrack.core.model

/**
 * The server-derived DISPLAY section of a library entry — what the Home screen groups by. It is
 * not [UserMediaStatus]: a `WATCHING` entry splits into [WATCHING] (episodes waiting) and
 * [CAUGHT_UP] (nothing unwatched right now), a distinction only the server can make from the air
 * dates. The client never derives it.
 *
 * [UNKNOWN] is a client-only placeholder for a value a newer server added (or an older one omitted).
 */
enum class LibrarySection {
    WATCHING,
    CAUGHT_UP,
    PAUSED,
    PLANNED,
    COMPLETED,
    DROPPED,
    UNKNOWN,
    ;

    /** The lower-case spelling the server uses. [UNKNOWN] is never a wire value, so it throws. */
    fun wireValue(): String {
        require(this != UNKNOWN) { "UNKNOWN is not a wire value" }
        return name.lowercase()
    }

    companion object {
        /**
         * Sections Home renders, in order. [CAUGHT_UP] has no header of its own: those entries
         * render inside [WATCHING]. [UNKNOWN] is not shown.
         */
        val homeOrder: List<LibrarySection> = listOf(WATCHING, PAUSED, PLANNED, COMPLETED, DROPPED)
    }
}
