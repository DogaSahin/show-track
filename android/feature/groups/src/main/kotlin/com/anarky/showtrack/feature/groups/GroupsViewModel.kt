package com.anarky.showtrack.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The groups list screen (task 9c.1).
 *
 * The constructor names ONE interface from `:core:data` — architecture rule 2, structural rather
 * than a review item, the same shape `FavoritesViewModel`/`ImportViewModel` use.
 *
 * `state` is a plain [MutableStateFlow], not `stateIn(WhileSubscribed(5_000))` (decision C-U):
 * [GroupRepository.groups]/[GroupRepository.createGroup]/[GroupRepository.joinGroup] are one-shot
 * suspend calls this ViewModel drives itself, with no Room-backed upstream to gate a subscription
 * against — `FavoritesViewModel`'s identical reasoning.
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
         * function again: the fresh [GroupsUiState.Success] this produces has no [GroupsUiState.Success.justCreated]
         * at all, regardless of what the state before this call carried.
         *
         * On failure, the same [GroupsUiState.Success.isStale] marking `FavoritesViewModel.refresh`
         * uses: a resume's failed background fetch marks a populated screen stale instead of
         * destroying it; only a load with nothing already on screen produces [GroupsUiState.Error].
         */
        fun refresh() {
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
                }
            }
        }

        /**
         * A no-op unless [state] is already [GroupsUiState.Success] (a group cannot be created
         * before the list itself has ever loaded) or a create is already in flight — the same
         * re-entrancy guard `FavoritesViewModel.loadMore` uses, applied to a form submit instead of
         * a scroll trigger: without it, a double-tap on the submit button would race two
         * `POST /v1/groups` calls and create the group twice.
         *
         * [GroupsUiState.Success.createError] is cleared THE MOMENT this launches (decision C-S:
         * clear the error before the retry, not only on success) — the same discipline
         * `ImportViewModel.import` follows for its own single form.
         *
         * On success, the created [com.anarky.showtrack.core.model.Group] is appended to [groups]
         * and the full [com.anarky.showtrack.core.data.repository.GroupWithInvite] is published as
         * [GroupsUiState.Success.justCreated] — this is one of only three moments the client ever
         * sees an invite code (task brief, E-I): `POST /v1/groups` is the only endpoint that
         * returns one for a group this session did not already know about.
         */
        fun createGroup(name: String) {
            val current = mutableState.value as? GroupsUiState.Success ?: return
            if (current.creating) return
            mutableState.value = current.copy(creating = true, createError = null)
            viewModelScope.launch {
                try {
                    val created = repository.createGroup(name)
                    replaceSuccess {
                        it.copy(
                            groups = it.groups + created.group,
                            justCreated = created,
                            creating = false,
                            createError = null,
                        )
                    }
                } catch (failure: GroupOperationException) {
                    replaceSuccess { it.copy(creating = false, createError = failure.failure) }
                }
            }
        }

        /**
         * [current.joining]'s re-entrancy guard mirrors [createGroup]'s own — a double-tap on
         * submit must not race two `POST /v1/groups/join` calls for the same code.
         *
         * [GroupsUiState.Success.joinError] is cleared before this launches (decision C-S), the
         * same discipline [createGroup] follows for its own channel — SEPARATE from [createGroup]'s
         * [GroupsUiState.Success.createError], since a failed join must never be readable as a
         * failed create or vice versa.
         *
         * [inviteCode] itself is never touched here — this function has no field of its own to
         * clear it from. [GroupsScreen]'s join dialog owns that text as its own `remember`ed draft,
         * exactly `ImportScreen`'s `username`, so a failed attempt leaves it exactly as the user
         * typed it: retyping a 20-character invite code because the request failed would be a bad
         * experience, and nothing in this function's failure path touches anything the screen reads
         * to populate that field.
         */
        fun joinGroup(inviteCode: String) {
            val current = mutableState.value as? GroupsUiState.Success ?: return
            if (current.joining) return
            mutableState.value = current.copy(joining = true, joinError = null)
            viewModelScope.launch {
                try {
                    val joined = repository.joinGroup(inviteCode)
                    replaceSuccess {
                        it.copy(
                            groups = it.groups + joined.group,
                            justCreated = joined,
                            joining = false,
                            joinError = null,
                        )
                    }
                } catch (failure: GroupOperationException) {
                    replaceSuccess { it.copy(joining = false, joinError = failure.failure) }
                }
            }
        }

        /**
         * The invite-code banner's own dismissal — a user reading it and moving on should not have
         * to wait for the next [refresh] (which would also clear it, just later) to make it go
         * away. Never re-derives a code: this only ever clears the field to `null`.
         */
        fun dismissJustCreated() {
            replaceSuccess { it.copy(justCreated = null) }
        }

        private inline fun replaceSuccess(transform: (GroupsUiState.Success) -> GroupsUiState.Success) {
            val latest = mutableState.value as? GroupsUiState.Success ?: return
            mutableState.value = transform(latest)
        }
    }
