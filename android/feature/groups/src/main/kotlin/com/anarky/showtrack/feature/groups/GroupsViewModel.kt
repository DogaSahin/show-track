package com.anarky.showtrack.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The groups list screen (task 9c.1; state split into [state]/[actionState] in fix round 1 — see
 * [GroupsActionState]'s own KDoc for the bug that forced the split).
 *
 * The constructor names ONE interface from `:core:data` — architecture rule 2, structural rather
 * than a review item, the same shape `FavoritesViewModel`/`ImportViewModel` use.
 *
 * [state] and [actionState] are both plain [MutableStateFlow]s, not `stateIn(WhileSubscribed(5_000))`
 * (decision C-U): [GroupRepository.groups]/[GroupRepository.createGroup]/[GroupRepository.joinGroup]
 * are one-shot suspend calls this ViewModel drives itself, with no Room-backed upstream to gate a
 * subscription against — `FavoritesViewModel`'s identical reasoning.
 *
 * Every call this class makes into [repository] can only ever throw [GroupOperationException]
 * (or [kotlinx.coroutines.CancellationException], which is never caught here and so propagates
 * untouched) — [GroupRepository]'s own `guarded` wraps every other failure at the `:core:data`
 * boundary (decision C-R; see that function's KDoc in `GroupRepositoryImpl.kt`). Catching the
 * specific exception type, rather than a generic `Exception`, is what lets every `catch` block
 * below read `.failure` — a real [com.anarky.showtrack.core.model.GroupFailure] — with no
 * `@Suppress("TooGenericExceptionCaught")` and no risk of quietly absorbing something that was
 * never meant to be a UI-facing failure.
 *
 * **No `init { refresh() }`**, `FavoritesViewModel`'s identical reasoning: [GroupsScreen]'s
 * `LifecycleResumeEffect` already fires on the very first composition, and [refresh] is a real
 * network round trip — an `init` block here would be a second, redundant `GET /v1/groups` on
 * every first open, not a gap the resume effect misses.
 */
@HiltViewModel
class GroupsViewModel
    @Inject
    constructor(
        private val repository: GroupRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<GroupsUiState>(GroupsUiState.Loading)
        val state: StateFlow<GroupsUiState> = mutableState.asStateFlow()

        private val mutableActionState = MutableStateFlow(GroupsActionState())
        val actionState: StateFlow<GroupsActionState> = mutableActionState.asStateFlow()

        // Guards [refresh] against re-entrancy (task 9c.8 round 2, review finding — the REACHABLE
        // gap this class had, as opposed to the unreachable "flagless" one documented on [refresh]
        // itself): `GroupsScreen` wires the SAME function to both `LifecycleResumeEffect` and
        // `onRetry`, the exact double-caller shape Step 2 fixed for `FavoritesViewModel.refresh`/
        // `ProfileViewModel.refreshStats` — a manual retry can land while a resume-triggered fetch
        // is still in flight. A private, state-shape-independent field, `FavoritesViewModel.refreshInFlight`'s
        // identical reasoning: [refresh] can be called while [state] is [GroupsUiState.Loading] or
        // [GroupsUiState.Error] too. A DROPPED re-entrant call, not a coalesced one (decision M2,
        // task 9c.8) — the next resume corrects a dropped one.
        private var refreshInFlight = false

        /**
         * Called from the initial resume (there is no `init` — see this class's own KDoc) and from
         * [GroupsUiState.Error]'s retry action.
         *
         * [GroupsUiState.Loading] is written wholesale ONLY when [state] is not already
         * [GroupsUiState.Success] — `FavoritesViewModel.refresh`'s identical guard, for the
         * identical reason: right for the first load and for a retry from [GroupsUiState.Error]
         * (decision C-S), wrong for a resume over an already-populated screen, which must keep the
         * existing rows on screen for the round trip rather than blanking to a full-screen spinner.
         *
         * On success, [GroupsUiState.Success.justCreated] is dropped unconditionally — never
         * carried forward from whatever [state] held before this call. This is what makes "the
         * invite code is not shown for a group that came from the list" true even after the
         * round trip create -> tap the new group -> Detail -> Back, which fires this exact
         * function again: the fresh [GroupsUiState.Success] this produces has no
         * [GroupsUiState.Success.justCreated] at all, regardless of what the state before this
         * call carried.
         *
         * On failure, the same [GroupsUiState.Success.isStale] marking `FavoritesViewModel.refresh`
         * uses: a resume's failed background fetch marks a populated screen stale instead of
         * destroying it; only a load with nothing already on screen produces [GroupsUiState.Error].
         *
         * **Fix round 1 — this function never touches [actionState].** Before the fix, a resume's
         * `refresh()` overwrote the WHOLE `Success` object, including the `creating`/`joining`
         * flags that used to live on it — so a create/join genuinely in flight when the app was
         * backgrounded and resumed had its "in progress" flag silently cleared the instant the
         * resume's OWN fetch landed, before the create/join itself had answered. The submit button
         * re-enabled and its label flipped back from "Creating…" while the original request was
         * still in flight, and a second tap raced a real second `POST /v1/groups`. Splitting
         * [actionState] out fixes this structurally: [refresh] has no way to reach it at all now.
         *
         * **Known gap, not fixed here (task 9c.8 round 1, review finding M1's own aside): this
         * function is the "flagless" sibling of [createGroup]/[joinGroup] and carries no `finally`.**
         * Those two guard a `Boolean`-shaped flag that a `finally` can unconditionally reset; this
         * function's own "flag" IS [GroupsUiState.Loading] itself, and the ONLY safe values to
         * replace it with on an unrecognised failure are [GroupsUiState.Error] (needs the
         * [GroupFailure] this class's `catch` deliberately never widens to construct from an
         * arbitrary [Throwable] — see this class's own KDoc for why that narrowing is load-bearing,
         * not incidental) or the previous [GroupsUiState.Success] (already handled — see above). A
         * [kotlinx.coroutines.CancellationException] escaping [repository.groups] (e.g. from an
         * internal `withTimeout`, never wrapped by `guarded` — this class's own KDoc) therefore
         * still leaves [GroupsUiState.Loading] stuck with no retry affordance, on the FIRST load or
         * a retry from [GroupsUiState.Error] specifically (a resume over an already-[GroupsUiState.Success]
         * screen is unaffected — [state] simply stays the last good [GroupsUiState.Success]). Fixing
         * it properly needs either widening this class's own catch (undoing the `.failure`-with-no-
         * suppression discipline every other function here relies on) or a generic fallback
         * [GroupFailure] built from an unknown [Throwable] — both bigger than a `finally` and out of
         * this round's scope. Flagged rather than silently left; not one of the five this round
         * actually fixes.
         */
        fun refresh() {
            if (refreshInFlight) return
            refreshInFlight = true
            if (mutableState.value !is GroupsUiState.Success) {
                mutableState.value = GroupsUiState.Loading
            }
            viewModelScope.launch {
                try {
                    val groups = repository.groups()
                    mutableState.value = GroupsUiState.Success(groups = groups)
                } catch (failure: GroupOperationException) {
                    val stillShowing = mutableState.value as? GroupsUiState.Success
                    mutableState.value = stillShowing?.copy(isStale = true) ?: GroupsUiState.Error(failure.failure)
                } finally {
                    refreshInFlight = false
                }
            }
        }

        /**
         * Re-entrancy is keyed on [GroupsActionState.creating] alone (fix round 1) — NOT on
         * whether [state] is [GroupsUiState.Success]. Before the fix, this function opened with
         * `mutableState.value as? GroupsUiState.Success ?: return`, which read as a re-entrancy
         * guard but was actually also a "has the list ever loaded" precondition with no caller
         * that wanted it: a user holding a valid invite code, or naming a new group, has every
         * reason to do so from a screen that failed to load the list (`GroupsUiState.Error`,
         * which — unlike `Loading` — is NOT transient; a cold start with no connectivity leaves it
         * there indefinitely) or hasn't finished loading it yet (`GroupsUiState.Loading`). That
         * guard made Create and Join permanently, silently dead from `Error`: the dialog opened,
         * accepted input, the submit button was enabled (nothing in the dialog itself reads
         * [state]), and tapping it called this function, which returned immediately — no request,
         * no error, no spinner, forever. See [GroupsActionState]'s own KDoc for the general
         * pattern this was an instance of.
         *
         * [GroupsActionState.createError] is cleared THE MOMENT this launches (decision C-S: clear
         * the error before the retry, not only on success) — the same discipline
         * `ImportViewModel.import` follows for its own single form.
         *
         * On success, [applyGroupChange] folds the created group into whatever [state] currently
         * holds — see that function's own KDoc for why it never re-fetches the whole list.
         *
         * **`finally` added, task 9c.8 round 1 (review finding M1).** The `catch` above only names
         * [GroupOperationException] (this class's own KDoc explains why), so anything else escaping
         * [repository.createGroup] — a [kotlinx.coroutines.CancellationException] from an internal
         * `withTimeout`, say — used to leave [GroupsActionState.creating] stuck `true` forever: not
         * merely a wedged pager the way `GroupDetailViewModel.loadMoreWatchlist`'s finding was, but
         * a permanently DISABLED submit button — worse, since there is no footer retry affordance
         * for a stuck action flag the way there is for a stuck page-fetch flag. Mirrors
         * `GroupDetailViewModel.loadMoreWatchlist`'s own `finally`: no `return@launch` (would
         * swallow an in-flight exception instead of letting it propagate), and only writes when
         * [GroupsActionState.creating] is still `true` (a no-op on the two paths above that already
         * cleared it).
         */
        fun createGroup(name: String) {
            if (mutableActionState.value.creating) return
            mutableActionState.value = mutableActionState.value.copy(creating = true, createError = null)
            viewModelScope.launch {
                try {
                    val created = repository.createGroup(name)
                    mutableActionState.value = mutableActionState.value.copy(creating = false, createError = null)
                    applyGroupChange(created)
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(creating = false, createError = failure.failure)
                } finally {
                    if (mutableActionState.value.creating) {
                        mutableActionState.value = mutableActionState.value.copy(creating = false)
                    }
                }
            }
        }

        /**
         * [GroupsActionState.joining]'s re-entrancy guard mirrors [createGroup]'s own, fixed the
         * identical way in this round — keyed on [actionState], not on [state].
         *
         * [GroupsActionState.joinError] is cleared before this launches (decision C-S), the same
         * discipline [createGroup] follows for its own channel — SEPARATE from [createGroup]'s
         * [GroupsActionState.createError], since a failed join must never be readable as a failed
         * create or vice versa.
         *
         * [inviteCode] itself is never touched here — this function has no field of its own to
         * clear it from. [JoinGroupDialog] owns that text as its own `remember`ed draft, exactly
         * `ImportScreen`'s `username`, so a failed attempt leaves it exactly as the user typed it:
         * retyping a 20-character invite code because the request failed would be a bad
         * experience, and nothing in this function's failure path touches anything the screen
         * reads to populate that field.
         *
         * `finally` added, task 9c.8 round 1 (review finding M1) — [createGroup]'s identical fix
         * and identical reasoning, applied to [GroupsActionState.joining] instead of `.creating`.
         */
        fun joinGroup(inviteCode: String) {
            if (mutableActionState.value.joining) return
            mutableActionState.value = mutableActionState.value.copy(joining = true, joinError = null)
            viewModelScope.launch {
                try {
                    val joined = repository.joinGroup(inviteCode)
                    mutableActionState.value = mutableActionState.value.copy(joining = false, joinError = null)
                    applyGroupChange(joined)
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(joining = false, joinError = failure.failure)
                } finally {
                    if (mutableActionState.value.joining) {
                        mutableActionState.value = mutableActionState.value.copy(joining = false)
                    }
                }
            }
        }

        /**
         * Folds a successful create/join into [state] (fix round 1). Appends [invite]'s group to
         * whatever [GroupsUiState.Success.groups] is already showing, or starts a fresh
         * single-group list if [state] is [GroupsUiState.Loading]/[GroupsUiState.Error] — the
         * server-side action already succeeded by the time this runs, so [state] MUST land on
         * [GroupsUiState.Success] regardless of what it was before, with [invite] published as
         * [GroupsUiState.Success.justCreated] (E-I: one of the only three moments the client ever
         * sees an invite code). Appending a genuinely NEW group LAST is not just convenient — the
         * server orders `GET /v1/groups` by `created_at ASC` (confirmed in review), so this keeps
         * client-side order matching the canonical one at all times, not only after the next
         * [refresh].
         *
         * Deliberately does NOT re-fetch [GroupRepository.groups] to get a "complete" list instead
         * of appending — that would add a second network call whose own failure would have to be
         * handled separately (the group was already created/joined server-side; a failed re-fetch
         * must not be reported as if the create/join itself failed), for a completeness guarantee
         * this screen does not need: the next resume's [refresh] re-fetches the canonical list
         * anyway, exactly as it would for any other out-of-band change made elsewhere.
         *
         * **Fix round 2, three findings, all repaired here:**
         *
         * 1. **[GroupRepository.joinGroup] is deliberately idempotent** — decision G-I,
         *    `backend/app/groups/service.py`'s `join_by_code`: redeeming a code for a group you
         *    already belong to is the server's documented happy path, returning 200 with that
         *    SAME group, not an error. Appending unconditionally duplicated it in [previousGroups],
         *    and `GroupsList`'s `LazyColumn` — keyed by `Group::id` — crashed composition
         *    (`IllegalArgumentException: Key "…" was already used`) the moment that render was
         *    attempted. A rejoin now replaces the matching row IN PLACE (fix round 3 — see below
         *    for why "in place" specifically) instead of duplicating it — correct for `createGroup`
         *    too, harmlessly: a freshly created group's id cannot already be in [previousGroups].
         * 2. **A successful create/join used to silently clear [GroupsUiState.Success.isStale].**
         *    This function used to construct a brand-new `Success(...)` rather than `copy` an
         *    existing one, so `isStale` always reset to its `false` default — an UNRELATED
         *    successful action erased the one signal (banner + Retry) telling the user their list
         *    might be out of date. [isStale] below now carries the previous value forward instead.
         * 3. **Create/join from [GroupsUiState.Error]/[GroupsUiState.Loading] asserted a complete
         *    list the ViewModel knew it never loaded**, with no in-screen way back — `ErrorState`'s
         *    and `StaleDataBanner`'s Retry affordances are both gone once [state] is
         *    [GroupsUiState.Success]. [isStale] below is `true` whenever [state] was NOT already
         *    [GroupsUiState.Success] — the list genuinely is known-incomplete in that case, so the
         *    banner and its Retry are exactly the right affordance, and the next successful
         *    [refresh] clears it exactly as it already does for an ordinary stale mark.
         *
         * **Fix round 3 (review finding): a rejoin is a real in-place `map`, not a remove-then-append.**
         * Round 2's own fix (item 1 above) actually REMOVED the matching row before appending
         * [invite]'s group at the end — which stops the crash, but visibly moves a rejoined group
         * to the bottom of the list until the next [refresh] restores its real position, an
         * undocumented reorder round 2's own KDoc and report both mis-described as an in-place
         * replace. [map] now updates the matching row WITHOUT moving it, and the append-if-absent
         * branch below is reached only for a genuinely new id — so client-side order never diverges
         * from the server's `created_at ASC` ordering at all, not merely after the next resume.
         */
        private fun applyGroupChange(invite: GroupWithInvite) {
            val previous = mutableState.value as? GroupsUiState.Success
            val previousGroups = previous?.groups.orEmpty()
            val groups =
                if (previousGroups.any { it.id == invite.group.id }) {
                    previousGroups.map { if (it.id == invite.group.id) invite.group else it }
                } else {
                    previousGroups + invite.group
                }
            mutableState.value =
                GroupsUiState.Success(
                    groups = groups,
                    justCreated = invite,
                    isStale = previous?.isStale ?: true,
                )
        }

        /**
         * The invite-code banner's own dismissal — a user reading it and moving on should not have
         * to wait for the next [refresh] (which would also clear it, just later) to make it go
         * away. Never re-derives a code: this only ever clears the field to `null`.
         */
        fun dismissJustCreated() {
            val current = mutableState.value as? GroupsUiState.Success ?: return
            mutableState.value = current.copy(justCreated = null)
        }

        /**
         * Clears a stale [GroupsActionState.createError] the moment the create dialog is (re)opened
         * (fix round 2, small item 3) — without this, reopening the dialog after a failed attempt
         * showed the PREVIOUS attempt's error before the user had done anything this time, which
         * reads as though the fresh open itself already failed. `GroupsScreen.kt`'s `GroupsTopBar`
         * `onCreateClick` binding calls this alongside setting the dialog visible.
         */
        fun clearCreateError() {
            mutableActionState.value = mutableActionState.value.copy(createError = null)
        }

        /** [clearCreateError]'s mirror for the join dialog — a SEPARATE channel, cleared separately. */
        fun clearJoinError() {
            mutableActionState.value = mutableActionState.value.copy(joinError = null)
        }
    }
