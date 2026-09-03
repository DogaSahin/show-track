package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure

/**
 * The groups LIST screen's state (task 9c.1). A closed sealed hierarchy rather than a bag of
 * booleans, the same reason `FavoritesUiState`/`LibraryUiState` are: a `when` over this cannot
 * represent "loading AND showing an error AND holding a stale list" all at once.
 *
 * Deliberately carries NO create/join fields — see [GroupsActionState]'s own KDoc for why those
 * live in a second, independent piece of state rather than here (fix round 1: the "have we ever
 * loaded a list" question this type answers and the "is a create/join in flight" question
 * [GroupsActionState] answers are two different callers, and a single `Success`-cast guard used
 * to serve both — see [GroupsViewModel]'s own KDoc for the bug that produced and the Global
 * Constraints rule it violated).
 */
sealed interface GroupsUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : GroupsUiState

    /**
     * [justCreated] is decision E-I made real: `GET /v1/groups` (what [groups] is built from)
     * returns `GroupRead`, which carries no invite code — the server withholds it deliberately,
     * since it is a credential (see this task's brief). [justCreated] is the ONLY place this
     * screen ever holds one, set exclusively by [GroupsViewModel.createGroup]/
     * [GroupsViewModel.joinGroup]'s own success path (via [GroupsViewModel.applyGroupChange]) and
     * cleared by the very next [GroupsViewModel.refresh] — never carried forward across a resume,
     * and never derived from [groups]. Held here, in memory, and NEVER persisted (not
     * `ActiveGroupStore`, not Room): a member who needs the code again rotates it, the server's
     * own position (`GroupRepository.rotateInvite`).
     *
     * [isStale] mirrors `FavoritesUiState.Success.isStale`/`LibraryUiState.Success.isStale` — the
     * settled refresh shape (Global Constraints): a failed background [GroupsViewModel.refresh]
     * over an already-populated screen marks the existing [groups] stale rather than blanking or
     * error-ing them away, and the next successful refresh clears it.
     *
     * **Fix round 1 — is `isStale` even meaningful here, given groups have no Room cache?** Yes,
     * for the identical reason it is meaningful on `FavoritesUiState.Success` (also network-only,
     * no Room-backed upstream — see that type's own KDoc): "stale" never meant "persisted to
     * disk" for either screen, it means "the last successful in-memory fetch is what's still on
     * screen, and a background re-fetch since then has failed." That fact is real and worth
     * surfacing regardless of where the data lives between fetches. What WAS wrong was the shared
     * `StaleDataBanner`'s hardcoded copy — "Showing saved titles" names the wrong noun for a list
     * of groups — fixed by giving that component a `messageRes` parameter (decision C-T: widen the
     * shared component rather than fork it) and passing `R.string.groups_stale_notice` from
     * `GroupsScreen.kt`.
     */
    data class Success(
        val groups: List<Group>,
        val justCreated: GroupWithInvite? = null,
        val isStale: Boolean = false,
    ) : GroupsUiState

    /** Only a failed [GroupsViewModel.refresh] with nothing already on screen ever produces this. */
    data class Error(
        val cause: GroupFailure,
    ) : GroupsUiState
}

/**
 * The create/join forms' own state — independent of [GroupsUiState] (fix round 1). [creating]/
 * [createError] and [joining]/[joinError] are two SEPARATE channels (decision C-S), not one
 * shared "actionError" — a failed create must never be readable as a failed join or vice versa,
 * and each guards its own re-entrancy (`GroupsViewModel.createGroup`/`joinGroup` both drop a
 * re-entrant call while their own flag is set, keyed on THIS type, never on [GroupsUiState]). Both
 * error fields are cleared the moment their own retry launches, not only on success —
 * `ImportViewModel.import`'s identical discipline, applied twice here since this screen has two
 * independent forms instead of one.
 *
 * **Why a second type, not two more fields on `GroupsUiState.Success`** (the shape this task
 * shipped with before this fix round, and the root cause of BLOCKING 1 in review): a
 * `mutableState.value as? GroupsUiState.Success ?: return` guard at the top of `createGroup`/
 * `joinGroup` was serving two unrelated jobs at once — re-entrancy (has a create/join for THIS
 * action already started) and "has the list ever loaded" (an accident of where the fields lived,
 * not a real precondition: a user holding a valid invite code has every reason to redeem it from
 * a screen that failed to load the list, or hasn't finished loading it yet). The guard could not
 * tell these apart, so a cold start with no connectivity — `Error` is not transient, unlike
 * `Loading` — left Create and Join permanently, silently dead: the dialog opened, accepted input,
 * the submit button was enabled, and tapping it did nothing at all, forever. This is the same
 * class of bug Global Constraints names four other instances of (`refresh()` doing both push and
 * stats; a `markSignedIn` guard conflating a second registration with a demotion; `AppStart`
 * standing in for navigation provenance; `applyCurrentFilter` blanking for a retry as well as a
 * filter switch) — one function/state shape serving two callers with different requirements. The
 * fix is the same each time: split at the definition, so re-entrancy has its own state
 * ([GroupsActionState]) that exists and is checkable regardless of what [GroupsUiState] currently
 * holds.
 */
data class GroupsActionState(
    val creating: Boolean = false,
    val createError: GroupFailure? = null,
    val joining: Boolean = false,
    val joinError: GroupFailure? = null,
)
