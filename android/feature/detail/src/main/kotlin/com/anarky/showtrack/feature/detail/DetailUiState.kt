package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media

/**
 * [entry] null means "not in your library" — a normal state, not an error. It is what makes the
 * primary action Add rather than Edit, and it is reachable from search and from a push deep-link
 * where no entry can exist yet (decision C-D).
 */
data class DetailData(
    val media: Media,
    val entry: LibraryEntry?,
)

/**
 * Which of this screen's two non-load operations most recently failed. A separate sealed type
 * rather than a bare `Throwable?` on [DetailUiState.Success] so the screen can tell an [Add]
 * failure — rendered beside the "Add to library" button — from an [Edit] failure — rendered
 * beside the score/progress/status/favourite controls — without smuggling that distinction
 * through the exception's own type. See [DetailViewModel]'s class KDoc for why load, add and
 * edit are three channels and not one.
 */
sealed interface DetailActionError {
    data class Add(
        val cause: Throwable,
    ) : DetailActionError

    data class Edit(
        val cause: Throwable,
    ) : DetailActionError
}

sealed interface DetailUiState {
    data object Loading : DetailUiState

    /**
     * [saving] disables the controls rather than swapping this whole case out for [Loading] —
     * the user must keep seeing what they are editing while an add/edit round-trips.
     *
     * [actionError] is the last [DetailActionError] from `addToLibrary()` or an edit, or null.
     * It is a field on THIS case, never a reason to fall through to [DetailUiState.Error]: unlike
     * the initial load, a failed add or edit leaves [data] exactly as it was (see
     * [DetailViewModel]'s KDoc) and the title stays fully on screen underneath it.
     */
    data class Success(
        val data: DetailData,
        val saving: Boolean = false,
        val actionError: DetailActionError? = null,
    ) : DetailUiState

    /** Only the initial load (or a retry of it) ever produces this — see [DetailViewModel]'s KDoc. */
    data class Error(
        val cause: Throwable,
    ) : DetailUiState
}
