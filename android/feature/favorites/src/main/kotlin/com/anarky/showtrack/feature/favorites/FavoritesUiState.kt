package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The Favorites tab: a podium of your top three scored favourites, then an Anime and a TV shelf.
 *
 * A closed hierarchy rather than a bag of booleans, for the same reason as every other screen here:
 * a `when` over it cannot represent "loading AND erroring AND showing stale rows" at once. Each
 * shelf pages on its own ([FavoriteShelf.loadingMore]/[FavoriteShelf.pageError]), and a failed page
 * never replaces the screen (decision C-S).
 */
sealed interface FavoritesUiState {
    data object Loading : FavoritesUiState

    /**
     * [podium] is in rank order (#1 first) and holds only scored favourites: up to four, the
     * podium's three plus one in reserve that steps up when a podium title is removed.
     * The podium titles also appear in their shelf on purpose: the podium is a highlight, the
     * shelves are the complete list.
     *
     * [isStale] is set when a refresh over this screen failed: what is shown is the last load that
     * succeeded, and the stale banner says so.
     */
    data class Success(
        val podium: List<LibraryEntry>,
        val anime: FavoriteShelf,
        val tv: FavoriteShelf,
        val isStale: Boolean = false,
    ) : FavoritesUiState {
        val isEmpty: Boolean get() = podium.isEmpty() && anime.entries.isEmpty() && tv.entries.isEmpty()
    }

    data class Error(
        val cause: Throwable,
    ) : FavoritesUiState
}

/** One shelf (or the See all grid): what is loaded, where the next page starts, and its own paging state. */
data class FavoriteShelf(
    val entries: List<LibraryEntry> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val pageError: Throwable? = null,
)

/** One-shot outcomes the screen turns into snackbars. */
sealed interface FavoritesEvent {
    /** The server accepted the removal; offer Undo. */
    data class Removed(
        val entry: LibraryEntry,
    ) : FavoritesEvent

    /** A removal or an undo was refused; the poster is already back where it was. */
    data object EditFailed : FavoritesEvent
}
