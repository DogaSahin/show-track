package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review

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

/**
 * The group section's own state (task 9c.6, decision E-J, design doc §3.5) — scoped to the ACTIVE
 * group only, never a merge across every group the account belongs to: there is no endpoint for
 * that, and merging would make "everyone's progress on this title" mean something different
 * depending on how many groups a reader happens to be in.
 *
 * [Absent] covers BOTH "the account is in no groups" (E-K's own no-groups state, which reaches this
 * screen too) and "the active group has not resolved yet" (`ActiveGroupState.Loading`/`Error`,
 * still being fetched one layer up in `:app`) — this screen's title is the primary content either
 * way, so an unresolved or failed groups fetch never gets its own full-screen treatment here the
 * way it does on Feed; the section simply renders nothing until a real group id arrives.
 *
 * [Loaded.isStale] is the settled refresh shape (Global Constraints), applied to this section the
 * same way `FeedUiState.Success.isStale` is applied to the feed: a reload failure over an
 * already-loaded section keeps what is already on screen and marks it possibly outdated rather than
 * replacing it with [Error] — only the FIRST fetch for a given group, with nothing loaded yet for
 * it, ever produces [Error]. An empty [Loaded] (both lists empty) is the honest "nobody else tracks
 * this title yet" outcome (§9.12's acceptance criterion) — it is a real, successful [Loaded], never
 * [Error] or [Absent].
 */
sealed interface GroupSectionState {
    /** No active group, or the active group has not resolved yet — nothing to show, nothing broken. */
    data object Absent : GroupSectionState

    data object Loading : GroupSectionState

    data class Loaded(
        val progress: List<MemberProgress>,
        val reviews: List<Review>,
        val isStale: Boolean = false,
    ) : GroupSectionState

    /** Only a fetch with nothing already loaded for the current group produces this — see the type KDoc. */
    data class Error(
        val cause: GroupFailure,
    ) : GroupSectionState
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
     *
     * [groupSection] (task 9c.6) is a FOURTH, independent channel — decision C-S, "one error
     * channel per operation, not one per screen" — carried one field further than [actionError]
     * already carries it: a failure loading everyone's progress/reviews must never touch [data],
     * [saving] or [actionError], and a failed add/edit must never touch [groupSection]. See
     * [DetailViewModel]'s KDoc for how the two channels are kept from clobbering each other.
     *
     * [proposing]/[proposeError] are the FIFTH channel, "propose this title to a group" (task
     * 9c.6, moved here from `:feature:groups` — see [GroupSectionState]'s own sibling KDoc and
     * `GroupSection.kt`). Independent of [groupSection] on purpose: the group a reader proposes TO
     * is not necessarily the ACTIVE group [groupSection] is scoped to — a member may want to share
     * a title with a group other than whichever one they are currently comparing progress against.
     */
    data class Success(
        val data: DetailData,
        val saving: Boolean = false,
        val actionError: DetailActionError? = null,
        val groupSection: GroupSectionState = GroupSectionState.Absent,
        val proposing: Boolean = false,
        val proposeError: GroupFailure? = null,
    ) : DetailUiState

    /** Only the initial load (or a retry of it) ever produces this — see [DetailViewModel]'s KDoc. */
    data class Error(
        val cause: Throwable,
    ) : DetailUiState
}
