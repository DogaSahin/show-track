package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember

/**
 * The group DETAIL screen's state (task 9c.2). [GroupsUiState]'s identical shape and identical
 * reasoning — a closed sealed hierarchy, not a bag of booleans — applied one screen over: a `when`
 * over this cannot represent "loading AND showing an error AND holding a stale member list" all at
 * once, and [GroupsUiState]'s own KDoc documents the two-round bug (a single `Success`-cast guard
 * serving both "have we ever loaded" and "is an action in flight") that a bag of booleans produced
 * the first time this project tried that shape. [GroupDetailActionState] exists for the identical
 * reason [GroupsActionState] does — see its own KDoc.
 *
 * Task 9c.3 extends [Success] with the shared watchlist (`GroupDetailUiState.kt`'s own file is
 * where the plan says that extension lands) — nothing here is written to preclude it.
 */
sealed interface GroupDetailUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : GroupDetailUiState

    /**
     * [members] and [currentUserId] are always fetched together, by the same [GroupDetailViewModel.refresh]
     * call — `GroupRepository.members(groupId)` and `GroupRepository.currentUserId()`. Owner-only
     * rendering (E-F) is derived from the two of them together, in [GroupDetailScreen] — a rendering
     * decision, not something either this state or [GroupDetailViewModel] pre-computes into a
     * boolean: "is this member me, and am I the owner" is cheap to recompute on every render and
     * keeping it un-cached is what makes E-F's stated mitigation ("derived from the live members
     * response... not from anything cached or inferred") literally true rather than merely intended.
     *
     * [rotatedInvite] mirrors [GroupsUiState.Success.justCreated] (decision E-I, restated for this
     * screen's own rotate action): the invite code returned by `GroupRepository.rotateInvite` is
     * shown ONCE, held only in this in-memory state, and is dropped — unconditionally, the identical
     * discipline [GroupsViewModel.refresh]'s own KDoc documents for `justCreated` — by the very next
     * [GroupDetailViewModel.refresh], including the refresh a successful [GroupDetailViewModel.removeMember]
     * triggers to reload the member list. That is deliberate, not an oversight: E-I's own position is
     * that a member who needs the code again rotates it, and nothing about "the owner just removed a
     * different member" changes that.
     *
     * [isStale] mirrors [GroupsUiState.Success.isStale] — the settled refresh shape (Global
     * Constraints): a failed background [GroupDetailViewModel.refresh] over an already-populated
     * screen marks the existing [members] stale rather than blanking or error-ing them away, and the
     * next successful refresh clears it.
     */
    data class Success(
        val members: List<GroupMember>,
        val currentUserId: String,
        val rotatedInvite: GroupWithInvite? = null,
        val isStale: Boolean = false,
    ) : GroupDetailUiState

    /** Only a failed [GroupDetailViewModel.refresh] with nothing already on screen ever produces this. */
    data class Error(
        val cause: GroupFailure,
    ) : GroupDetailUiState
}

/**
 * The three owner/member actions' own state, independent of [GroupDetailUiState] — [GroupsActionState]'s
 * own KDoc explains why a second, independent type exists at all rather than folding these fields
 * onto [GroupDetailUiState.Success]: the SAME bug that KDoc documents (a `Success`-cast guard
 * serving both "have we loaded" and "is an action in flight", which left Create/Join permanently
 * dead from [GroupsUiState.Error]) is exactly as reachable here — a member holding a valid reason to
 * leave a group has every reason to do so from a screen whose OWN member-list load just failed
 * ([GroupDetailUiState.Error] is not transient, the identical note [GroupsUiState.Error] carries).
 *
 * THREE separate channels (decision C-S: one error channel per operation, not one per screen), not
 * one shared "actionError": [rotating]/[rotateError] for the owner's rotate action,
 * [removingUserId]/[removeError] for the owner removing a DIFFERENT member, and [leaving]/[leaveError]
 * for any member leaving. Rotate and remove are already distinct per decision C-S's own logic
 * (different endpoints). Leave and remove are the SAME endpoint (`DELETE /members/{userId}`, design
 * doc §1.1) called with a different id, but kept on separate channels anyway: they have different
 * copy, different confirmation dialogs, and different success handling (leave navigates away; remove
 * refreshes the member list in place) — a guard belongs where the caller is known (Global
 * Constraints), and "which of the two this call site meant" is exactly a fact only the CALLER
 * (leave button vs. a specific row's remove button) has, not something a single shared flag could
 * reconstruct afterward. Sharing one flag would also make the owner's OWN row show "removing…" while
 * they tap Leave, and vice versa, for two actions that are conceptually unrelated to their caller
 * even though they land on the identical repository call underneath.
 *
 * [removingUserId] (rather than a bare `removing: Boolean`) names WHICH row's remove is in flight —
 * needed because [GroupDetailScreen] renders one remove control PER OTHER member, and a bare
 * boolean could not tell "disable every row's remove control" apart from "disable only the one
 * being removed", which is the wrong UX (racing two different members' removals is a real,
 * supportable case; the endpoint has no cross-member conflict to guard against, only re-entrancy on
 * the SAME id — enforced below by rejecting a second [GroupDetailViewModel.removeMember] while
 * [removingUserId] is already non-null, mirroring [GroupsActionState]'s re-entrancy guards).
 */
data class GroupDetailActionState(
    val rotating: Boolean = false,
    val rotateError: GroupFailure? = null,
    val removingUserId: String? = null,
    val removeError: GroupFailure? = null,
    val leaving: Boolean = false,
    val leaveError: GroupFailure? = null,
)
