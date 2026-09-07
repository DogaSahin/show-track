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

/**
 * What the review editor's last save attempt failed with (task 9c.7, decision C-S's "one error
 * channel per operation" carried one channel further, alongside [DetailActionError]/`proposeError`).
 * A dedicated sealed type rather than reusing [GroupFailure] directly for [BodyRequired]/
 * [BodyTooLong]: those two never reach the server at all — §3.6's 1-4000 character,
 * whitespace-stripped bound is enforced client-side, before any request is sent, so an
 * out-of-range body costs no round trip (`ReviewBody`'s server-side `min_length=1` is itself
 * checked AFTER stripping, `backend/app/library/schemas.py`, so an all-whitespace body is exactly
 * [BodyRequired], never a value the server would silently accept). Inventing [GroupFailure] cases
 * for something the server is never even asked about would misrepresent what that type means:
 * `GroupSection.kt`'s `GroupFailure.messageRes()` already commits to being exhaustive over exactly
 * the failures the SERVER can produce.
 */
sealed interface ReviewSaveError {
    data object BodyRequired : ReviewSaveError

    data object BodyTooLong : ReviewSaveError

    data class Remote(
        val failure: GroupFailure,
    ) : ReviewSaveError
}

/**
 * The review editor's own state (task 9c.7, E-G, design doc §3.6) — a SIXTH independent channel on
 * [DetailUiState.Success], alongside load/edit/[GroupSectionState]/propose (see that case's own
 * KDoc for why each stays independent rather than sharing one slot).
 *
 * [Open.reviewId] carries the same nullable convention [GroupRepository.updateReview]'s own KDoc
 * and [GroupFailure.AlreadyReviewed] already use: null means "this is a fresh draft, saving POSTs
 * it"; non-null means "PATCH this review". [DetailViewModel.openReviewEditor] resolves it up front
 * from whatever [DetailViewModel.findOwnReview] can find — the ALREADY-LOADED
 * [GroupSectionState.Loaded.reviews] the active group's section currently has, matched against the
 * signed-in account's own id, falling back to the last review THIS ViewModel instance itself
 * created or updated when there is no such loaded, matching section (fix round 1 — a no-groups
 * account was otherwise locked out of ever editing a review it had just written in this same
 * session; see [DetailViewModel.findOwnReview]'s own KDoc).
 *
 * [DetailViewModel.saveReview] resolves the SAME way if a fresh POST still 409s (the section had
 * not loaded yet when the editor opened, or a review was written from a second session since) —
 * see that function's own KDoc for why the 409 body itself carries no id to use instead
 * ([GroupFailure.AlreadyReviewed.existingReviewId]'s own KDoc). That resolution is NOT applied
 * silently: [confirmOverwrite] is what tells the screen the reader has never seen the review this
 * save is about to replace (fix round 1) — see [confirmOverwrite]'s own KDoc.
 *
 * [Open.seedBody]/[Open.seedContainsSpoilers] are read exactly ONCE, as the initial value of
 * [ReviewEditor]'s own `remember`ed draft — never patched back into this state field by field as
 * the reader types, the identical reasoning [GroupsDialogs.CreateGroupDialog]'s own `name` draft
 * gives for owning its text field state locally rather than routing every keystroke through a
 * ViewModel.
 */
sealed interface ReviewEditorState {
    data object Closed : ReviewEditorState

    /**
     * [confirmOverwrite] (fix round 1, BLOCKING B1's should-fix companion): true only for the ONE
     * turn where [DetailViewModel.handleSaveFailure] has just resolved a 409 into an existing
     * review the reader has never been shown — [reviewId] switches to that review's id so the
     * NEXT Save is a `PATCH`, but nothing is sent automatically, and [seedBody]/[seedContainsSpoilers]
     * are left exactly as the reader last typed them, never overwritten with the OLD review's own
     * text. The reader's own next tap of Save is the confirmation; no separate dialog, no second
     * control — the same button, now carrying a different meaning the copy states plainly. Cleared
     * on that same tap ([DetailViewModel.saveReview] resets it before relaunching).
     */
    data class Open(
        val reviewId: String?,
        val seedBody: String,
        val seedContainsSpoilers: Boolean,
        val saving: Boolean = false,
        val error: ReviewSaveError? = null,
        val confirmOverwrite: Boolean = false,
    ) : ReviewEditorState
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
     *
     * [justProposedToGroupId] (fix round 1 addition) is the ONLY feedback a successful propose
     * gets when the picker never opened at all — the `groups.size == 1` direct-propose path
     * ([GroupSection.kt]'s own KDoc) has no confirmation dialog to close, so without this field a
     * single-group user tapping "Propose to a group" sees literally nothing happen, indistinguishable
     * from a dead button.
     *
     * **Not** `GroupsUiState.Success.justCreated`/`GroupDetailUiState.Success.rotatedInvite`'s
     * discipline (fix round 2 correction — an earlier version of this KDoc claimed it was): those
     * two are cleared by an explicit dismiss AND dropped on the next refresh. This field has
     * neither — it is cleared ONLY by the very next [DetailViewModel.proposeToGroup] call, which
     * is deliberate: "Proposed to Alpha Watchers." is meant to stay under the button for the life
     * of this ViewModel instance, surviving an unrelated edit, a group-section reload, and a group
     * switch, until the reader either leaves the screen or proposes again (to the same group or a
     * different one). A reader who proposed once has no reason to have that fact hidden from them
     * by an action that has nothing to do with the propose they just made.
     *
     * [reviewEditor] (task 9c.7, E-G) is the SIXTH channel — see [ReviewEditorState]'s own KDoc.
     * Independent of [groupSection] and [proposing]/[proposeError]/[justProposedToGroupId] for the
     * identical decision-C-S reason those three are independent of EACH OTHER: writing a review is
     * a title action, not a group action (E-G's own reasoning), so a failed save must never touch
     * the group section's rows, and a group-section reload racing an in-flight save must never
     * touch [reviewEditor].
     */
    data class Success(
        val data: DetailData,
        val saving: Boolean = false,
        val actionError: DetailActionError? = null,
        val groupSection: GroupSectionState = GroupSectionState.Absent,
        val proposing: Boolean = false,
        val proposeError: GroupFailure? = null,
        val justProposedToGroupId: String? = null,
        val reviewEditor: ReviewEditorState = ReviewEditorState.Closed,
    ) : DetailUiState

    /** Only the initial load (or a retry of it) ever produces this — see [DetailViewModel]'s KDoc. */
    data class Error(
        val cause: Throwable,
    ) : DetailUiState
}
