package com.anarky.showtrack.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The group DETAIL screen (task 9c.2). [groupId] is read once, in the constructor, from
 * [savedStateHandle] via [androidx.navigation.toRoute] — `DetailViewModel`'s identical pattern for
 * `DetailRoute.mediaId`: a route argument is a navigation key, not a data payload
 * ([com.anarky.showtrack.core.navigation.GroupDetailRoute]'s own KDoc), so this ViewModel re-fetches
 * its own state from the id rather than accepting a whole [com.anarky.showtrack.core.model.Group].
 *
 * **`init { refresh() }`, unlike [GroupsViewModel]** (which deliberately has none — see that
 * class's own KDoc). The difference is what each screen IS: `GroupsScreen` is revisited repeatedly
 * (a tab-adjacent list `GroupsScreen`'s `LifecycleResumeEffect` refreshes on every resume), while
 * this screen is a LEAF pushed fresh once per visit and torn down with its own `NavBackStackEntry`
 * on the way back — `DetailViewModel`'s identical shape and identical reasoning, not `GroupsViewModel`'s.
 *
 * Every call this class makes into [repository] can only ever throw [GroupOperationException] (or
 * [kotlinx.coroutines.CancellationException], never caught here) — [GroupsViewModel]'s own KDoc
 * explains why catching that specific type, not a generic `Exception`, is what lets every `catch`
 * block below read `.failure` with no `@Suppress("TooGenericExceptionCaught")`.
 */
@HiltViewModel
class GroupDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: GroupRepository,
    ) : ViewModel() {
        private val groupId: String = savedStateHandle.toRoute<GroupDetailRoute>().groupId

        private val mutableState = MutableStateFlow<GroupDetailUiState>(GroupDetailUiState.Loading)
        val state: StateFlow<GroupDetailUiState> = mutableState.asStateFlow()

        private val mutableActionState = MutableStateFlow(GroupDetailActionState())
        val actionState: StateFlow<GroupDetailActionState> = mutableActionState.asStateFlow()

        // `false` once and never reset, mirroring `ProfileViewModel.signedOut`'s identical shape
        // and identical reasoning: this ViewModel is scoped to the NavBackStackEntry and is torn
        // down the moment GroupDetailScreen navigates away on `true`, so there is no second leave
        // to observe.
        private val mutableLeft = MutableStateFlow(false)
        val left: StateFlow<Boolean> = mutableLeft.asStateFlow()

        init {
            refresh()
        }

        /**
         * Loads [GroupDetailUiState.Success.currentUserId] and [GroupDetailUiState.Success.members]
         * together — [GroupDetailUiState.Success]'s own KDoc explains why owner-only rendering (E-F)
         * needs both fetched together rather than the id resolved once and cached.
         *
         * The identical settled-refresh shape [GroupsViewModel.refresh] documents (Global
         * Constraints): [GroupDetailUiState.Loading] is written wholesale ONLY when [state] is not
         * already [GroupDetailUiState.Success] (right for the first load and a retry from
         * [GroupDetailUiState.Error]; wrong for [removeMember]'s own reload via [reloadMembers],
         * which must keep the existing rows on screen for that round trip).
         */
        fun refresh() {
            if (mutableState.value !is GroupDetailUiState.Success) {
                mutableState.value = GroupDetailUiState.Loading
            }
            viewModelScope.launch { reloadMembers() }
        }

        /**
         * The actual fetch [refresh] and [removeMember] share — pulled out so [removeMember] can
         * `await` the SAME reload rather than firing a second, unrelated coroutine via [refresh]
         * (an earlier version of this class did exactly that, which cleared
         * [GroupDetailActionState.removingUserId] the instant the DELETE itself returned, before the
         * follow-up `GET /members` had landed — a fast double-tap on "Remove" in that window could
         * fire a second DELETE for an already-removed id while the row was still visibly on screen).
         * Awaiting the reload here means [removeMember]'s "submitting" state, and the confirm dialog
         * it drives, stay true for the WHOLE round trip, not just the delete half of it.
         *
         * On success, [GroupDetailUiState.Success.rotatedInvite] is dropped unconditionally — never
         * carried forward from whatever [state] held before this call — the identical discipline
         * [GroupsViewModel.refresh] applies to `justCreated`, for the identical reason (E-I: a
         * refresh, including one [removeMember] triggers, is not a moment the server hands the
         * invite code back). On failure, an already-[GroupDetailUiState.Success] screen is marked
         * [GroupDetailUiState.Success.isStale] instead of replaced by [GroupDetailUiState.Error] —
         * only a load with nothing already on screen produces that; a reload failure right after a
         * successful [removeMember] is exactly this case, since the removal itself already
         * succeeded server-side by the time this could fail.
         */
        private suspend fun reloadMembers() {
            try {
                val userId = repository.currentUserId()
                val members = repository.members(groupId)
                mutableState.value = GroupDetailUiState.Success(members = members, currentUserId = userId)
            } catch (failure: GroupOperationException) {
                val stillShowing = mutableState.value as? GroupDetailUiState.Success
                mutableState.value = stillShowing?.copy(isStale = true) ?: GroupDetailUiState.Error(failure.failure)
            }
        }

        /**
         * Owner only — the server enforces it ([com.anarky.showtrack.core.model.GroupFailure.NotPermitted]
         * for a non-owner), and [GroupDetailScreen] hides the control for a non-owner in the first
         * place (E-F) — this function itself has no role check of its own, matching
         * [GroupsViewModel]'s identical division of labour between "the server is the real gate" and
         * "the UI's job is not to offer a dead end".
         *
         * On success, the fresh code is written into [GroupDetailUiState.Success.rotatedInvite] —
         * `GroupsViewModel.applyGroupChange`'s single-action mirror, here inline since there is only
         * one field to fold rather than a whole list. Silently does nothing to [state] if it is not
         * [GroupDetailUiState.Success] at that moment (the member list load having failed or still
         * being in flight makes the rotated code homeless — there is no member row to show it above
         * — a real but narrow edge the brief does not name a behaviour for; re-running [refresh]
         * afterward is what recovers it).
         */
        fun rotateInvite() {
            if (mutableActionState.value.rotating) return
            mutableActionState.value = mutableActionState.value.copy(rotating = true, rotateError = null)
            viewModelScope.launch {
                try {
                    val invite = repository.rotateInvite(groupId)
                    mutableActionState.value = mutableActionState.value.copy(rotating = false)
                    val current = mutableState.value as? GroupDetailUiState.Success
                    if (current != null) {
                        mutableState.value = current.copy(rotatedInvite = invite)
                    }
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(rotating = false, rotateError = failure.failure)
                }
            }
        }

        /**
         * `DELETE /v1/groups/{id}/members/{userId}` with the SIGNED-IN user's own id
         * ([GroupDetailUiState.Success.currentUserId]) — [removeMember]'s sibling, calling the
         * identical repository method with a different id (design doc §1.1). Kept as its own
         * function, on its own [GroupDetailActionState] channel, rather than
         * `removeMember(currentUserId)`: they have different success handling ([mutableLeft], not a
         * [refresh]) and — Global Constraints' "a guard belongs where the caller is known" — the
         * CALLER (the "Leave group" button) is what knows this is a leave, not a remove; recovering
         * that fact from the id alone downstream would be inferring it instead.
         *
         * No-ops if [state] is not [GroupDetailUiState.Success] — there is no
         * [GroupDetailUiState.Success.currentUserId] to leave with. [GroupDetailScreen] never
         * renders the "Leave group" affordance outside [GroupDetailUiState.Success] in the first
         * place, so this is a defensive `return`, not a reachable production path.
         */
        fun leaveGroup() {
            if (mutableActionState.value.leaving) return
            val userId = (mutableState.value as? GroupDetailUiState.Success)?.currentUserId ?: return
            mutableActionState.value = mutableActionState.value.copy(leaving = true, leaveError = null)
            viewModelScope.launch {
                try {
                    repository.removeMember(groupId, userId)
                    mutableActionState.value = mutableActionState.value.copy(leaving = false)
                    mutableLeft.value = true
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(leaving = false, leaveError = failure.failure)
                }
            }
        }

        /**
         * `DELETE /v1/groups/{id}/members/{userId}` with SOMEONE ELSE's id — owner only, and never
         * offered for the caller's own row ([GroupDetailScreen] never renders a "Remove" control next
         * to the signed-in member's own row — see [leaveGroup]'s own KDoc for why that is a
         * differently-worded call to the SAME endpoint, not this function called with a different
         * argument).
         *
         * Re-entrancy is keyed on [GroupDetailActionState.removingUserId] being non-null AT ALL, not
         * on it matching [userId] — a second remove for a DIFFERENT member while one is already in
         * flight is also dropped, since [GroupDetailActionState] has exactly one in-flight slot (its
         * own KDoc: two members racing their OWN removals independently is real and supportable, but
         * this ViewModel does not attempt to support two concurrent removes from the SAME actor —
         * the UI's confirm-dialog flow makes that an edge case rather than a normal one, and
         * supporting it would need a `Set<String>` in place of a single nullable id for no benefit
         * this brief asks for).
         *
         * On success, [reloadMembers] reloads the member list — the removed member's row
         * disappearing is what a real `GET /members` answers, not an optimistic local removal
         * (`DetailViewModel`'s own "no optimistic updates" discipline, applied here for the
         * identical reason: the server owns the member list, and a removal that raced a concurrent
         * add is what a real reload reflects correctly). [GroupDetailActionState.removingUserId]
         * stays non-null for the WHOLE round trip, including the reload — see [reloadMembers]'s own
         * KDoc for why that is not merely the delete call's own duration.
         */
        fun removeMember(userId: String) {
            if (mutableActionState.value.removingUserId != null) return
            mutableActionState.value = mutableActionState.value.copy(removingUserId = userId, removeError = null)
            viewModelScope.launch {
                try {
                    repository.removeMember(groupId, userId)
                    reloadMembers()
                    mutableActionState.value = mutableActionState.value.copy(removingUserId = null)
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(removingUserId = null, removeError = failure.failure)
                }
            }
        }

        /**
         * Clears a stale [GroupDetailActionState.rotateError] the moment the rotate confirmation
         * dialog is (re)opened — `GroupsViewModel.clearCreateError`'s identical reasoning and identical
         * fix-round-2 lesson, applied proactively here rather than rediscovered: without this,
         * reopening the dialog after a failed rotate shows the PREVIOUS attempt's error before the
         * user has done anything this time.
         */
        fun clearRotateError() {
            mutableActionState.value = mutableActionState.value.copy(rotateError = null)
        }

        /**
         * [clearRotateError]'s mirror for the remove-confirmation dialog — a SEPARATE channel,
         * cleared separately.
         */
        fun clearRemoveError() {
            mutableActionState.value = mutableActionState.value.copy(removeError = null)
        }

        /** [clearRotateError]'s mirror for the leave-confirmation dialog — a SEPARATE channel, cleared separately. */
        fun clearLeaveError() {
            mutableActionState.value = mutableActionState.value.copy(leaveError = null)
        }

        /**
         * The rotated-code banner's own dismissal — [GroupsViewModel.dismissJustCreated]'s identical
         * shape: a user reading it and moving on should not have to wait for the next [refresh]
         * (which would also clear it, just later — [GroupDetailUiState.Success]'s own KDoc) to make
         * it go away. Never re-derives a code: this only ever clears the field to `null`.
         */
        fun dismissRotatedInvite() {
            val current = mutableState.value as? GroupDetailUiState.Success ?: return
            mutableState.value = current.copy(rotatedInvite = null)
        }
    }
