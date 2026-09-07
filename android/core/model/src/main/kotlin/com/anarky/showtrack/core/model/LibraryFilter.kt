package com.anarky.showtrack.core.model

/** The sorts `GET /v1/library` accepts. [wire] is the exact query value the backend expects. */
enum class LibrarySort(
    val wire: String,
) {
    TITLE("title"),
    SCORE("score"),
    NEXT_EPISODE_DATE("next_episode_date"),
}

/**
 * [isDefault] is what decision C-B turns on: only the default view is cached in Room. Five
 * statuses times three sorts is fifteen combinations, and caching all of them would make Room a
 * queryable mirror of the server — the source-of-truth inversion architecture rule 2 forbids.
 */
data class LibraryFilter(
    val status: UserMediaStatus? = null,
    val sort: LibrarySort = LibrarySort.TITLE,
) {
    val isDefault: Boolean get() = status == null && sort == LibrarySort.TITLE
}
