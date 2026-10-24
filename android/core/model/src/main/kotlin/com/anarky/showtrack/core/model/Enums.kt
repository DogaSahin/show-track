package com.anarky.showtrack.core.model

// Every server-sourced enum ends in UNKNOWN: the member a value this client build does not know
// maps to (see the mappers), so a new server value degrades one row instead of failing a whole
// response. UNKNOWN is never sent to the server and never offered as a choice — pickers, tabs and
// filters iterate `selectable`.

enum class MediaType {
    ANIME,
    TV,
    UNKNOWN,
    ;

    companion object {
        /** The types a user can browse or filter by: every type except [UNKNOWN]. */
        val selectable: List<MediaType> = entries - UNKNOWN
    }
}

enum class MediaSource { ANILIST, TMDB, UNKNOWN }

enum class MediaStatus { AIRING, FINISHED, NOT_YET_AIRED, UNKNOWN }

enum class UserMediaStatus {
    WATCHING,
    COMPLETED,
    DROPPED,
    PLANNED,
    PAUSED,
    UNKNOWN,
    ;

    companion object {
        /** The statuses a user can set or filter by: every status except [UNKNOWN], in order. */
        val selectable: List<UserMediaStatus> = entries - UNKNOWN
    }
}
