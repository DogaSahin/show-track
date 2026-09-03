package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure

/**
 * The groups list screen's state (task 9c.1). A closed sealed hierarchy rather than a bag of
 * booleans, the same reason `FavoritesUiState`/`LibraryUiState` are: a `when` over this cannot
 * represent "loading AND showing an error AND holding a stale list" all at once.
 */
sealed interface GroupsUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : GroupsUiState

    /**
     * [justCreated] is decision E-I made real: `GET /v1/groups` (what [groups] is built from)
     * returns `GroupRead`, which carries no invite code — the server withholds it deliberately,
     * since it is a credential (see this task's brief). [justCreated] is the ONLY place this
     * screen ever holds one, set exclusively by [GroupsViewModel.createGroup]/
     * [GroupsViewModel.joinGroup]'s own success path and cleared by the very next
     * [GroupsViewModel.refresh] — never carried forward across a resume, and never derived from
     * [groups]. Held here, in memory, and NEVER persisted (not `ActiveGroupStore`, not Room): a
     * member who needs the code again rotates it, the server's own position (`GroupRepository.rotateInvite`).
     *
     * [isStale] mirrors `FavoritesUiState.Success.isStale`/`LibraryUiState.Success.isStale` — the
     * settled refresh shape (Global Constraints): a failed background [GroupsViewModel.refresh]
     * over an already-populated screen marks the existing [groups] stale rather than blanking or
     * error-ing them away, and the next successful refresh clears it.
     *
     * [creating]/[createError] and [joining]/[joinError] are two SEPARATE channels (decision
     * C-S), not one shared "actionError" — a failed create must never be readable as a failed
     * join or vice versa, and each guards its own re-entrancy (`GroupsViewModel.createGroup`/
     * `joinGroup` both drop a re-entrant call while their own flag is set). Both are cleared the
     * moment their own retry launches, not only on success — `ImportViewModel.import`'s identical
     * discipline, applied twice here since this screen has two independent forms instead of one.
     */
    data class Success(
        val groups: List<Group>,
        val justCreated: GroupWithInvite? = null,
        val isStale: Boolean = false,
        val creating: Boolean = false,
        val createError: GroupFailure? = null,
        val joining: Boolean = false,
        val joinError: GroupFailure? = null,
    ) : GroupsUiState

    /** Only a failed [GroupsViewModel.refresh] with nothing already on screen ever produces this. */
    data class Error(
        val cause: GroupFailure,
    ) : GroupsUiState
}
