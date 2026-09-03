package com.anarky.showtrack.feature.groups

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ShowTrackGroupDetail"

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
 * **[currentUserId] is resolved by its OWN coroutine, entirely independent of [reloadMembers]/
 * [state]** (round 1 review, BLOCKING 2/3 and the ruling that resolved them). Round 0 fetched
 * identity inside the SAME `try` as the member list, which meant a failed member-list load left no
 * id in existence at all, and every action needing it — leaving the group most of all — died
 * silently with the load. [AuthRepository.currentUserId]'s own KDoc has the full reasoning for why
 * identity moved there. `init` calls only [refresh], which itself calls [loadCurrentUserId] — see
 * that function's and [currentUserIdRequested]'s own KDocs for why the re-entrancy guard lives
 * there rather than as a `currentUserId.value == null` check at each call site, and why that
 * distinction is not cosmetic. [leaveGroup] does not even read the cached [currentUserId] field —
 * it asks [authRepository] directly, which is cheap after the first successful resolution (that
 * repository caches it for the session) and self-healing if the background resolution above
 * genuinely failed.
 *
 * Every call this class makes into [groupRepository] can only ever throw [GroupOperationException]
 * (or [kotlinx.coroutines.CancellationException], never caught here) — [GroupsViewModel]'s own KDoc
 * explains why catching that specific type, not a generic `Exception`, is what lets most `catch`
 * blocks below read `.failure` with no `@Suppress("TooGenericExceptionCaught")`. [leaveGroup] is the
 * one exception (also catches [authRepository]'s own failure type) and is suppressed explicitly.
 */
@HiltViewModel
class GroupDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val groupRepository: GroupRepository,
        private val authRepository: AuthRepository,
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

        // Session-lifetime identity — see this class's own KDoc and AuthRepository.currentUserId's
        // for why it is held here, in its OWN field, rather than as part of [GroupDetailUiState].
        // `null` means "not resolved yet" (or the background resolution below failed); the screen
        // treats that identically to "not the owner" for rendering (E-F: hidden, not disabled), and
        // [leaveGroup] never reads this field at all — see this class's own KDoc.
        private val mutableCurrentUserId = MutableStateFlow<String?>(null)
        val currentUserId: StateFlow<String?> = mutableCurrentUserId.asStateFlow()

        // A plain re-entrancy flag, not "currentUserId.value == null" read from [refresh] — that
        // was round 1's own first draft, and it raced: `init` calls [loadCurrentUserId] and then
        // [refresh] (which ALSO calls [loadCurrentUserId]) in the SAME synchronous block, before
        // either's own launched coroutine has had a chance to actually WRITE [mutableCurrentUserId],
        // so a `.value == null` check at that point cannot tell "never asked" apart from "asked,
        // still in flight" — both read `null`, and read it BEFORE the first request's own coroutine
        // has run. This flag is set synchronously, the instant [loadCurrentUserId] is called, so a
        // second call arriving before the first has finished (from anywhere, not just `init`'s own
        // ordering) is rejected deterministically regardless of dispatcher/scheduling timing.
        private var currentUserIdRequested = false

        init {
            refresh()
        }

        /**
         * Independent of [reloadMembers] — see this class's own KDoc — but now called FROM [refresh]
         * (as well as directly reachable were a caller to need it) rather than a sibling `init` call,
         * since [currentUserIdRequested] is what actually prevents the duplicate-call race described
         * on that field's own KDoc, not the call site. Failure is logged, not surfaced as a screen
         * error: there is no dedicated UI state for "we don't know who you are yet", and hiding
         * owner-only controls (their only real consequence — [GroupDetailScreen] treats a `null`
         * [currentUserId] as "not the owner") is exactly E-F's own stated behaviour for an unknown
         * role. [leaveGroup] is unaffected by this failing, since it never reads [currentUserId] — it
         * re-asks [authRepository] itself. A failure resets [currentUserIdRequested], which is what
         * lets the NEXT [refresh] retry rather than being permanently rejected by this same guard.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun loadCurrentUserId() {
            if (currentUserIdRequested) return
            currentUserIdRequested = true
            viewModelScope.launch {
                try {
                    mutableCurrentUserId.value = authRepository.currentUserId()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    currentUserIdRequested = false
                    Log.w(TAG, "could not resolve the signed-in user's id: ${failure.javaClass.simpleName}")
                }
            }
        }

        /**
         * The identical settled-refresh shape [GroupsViewModel.refresh] documents (Global
         * Constraints): [GroupDetailUiState.Loading] is written wholesale ONLY when [state] is not
         * already [GroupDetailUiState.Success] (right for the first load and a retry from
         * [GroupDetailUiState.Error]; wrong for [removeMember]'s own reload via [reloadMembers],
         * which must keep the existing rows on screen for that round trip).
         *
         * Also calls [loadCurrentUserId] unconditionally — a genuine no-op once a resolution is
         * already in flight or already succeeded ([currentUserIdRequested]'s own KDoc), and the
         * retry path for a PREVIOUSLY FAILED resolution: the user tapping Retry is the identical
         * trigger this function already recovers [reloadMembers] from, applied to identity too.
         */
        fun refresh() {
            loadCurrentUserId()
            if (mutableState.value !is GroupDetailUiState.Success) {
                mutableState.value = GroupDetailUiState.Loading
            }
            // false: an ordinary refresh drops any rotated code on screen unconditionally — see
            // reloadMembers's own KDoc (E-I: a refresh is not a moment the server hands the invite
            // code back).
            viewModelScope.launch { reloadMembers(preserveRotatedInvite = false) }
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
         * Only [members] is fetched here now (round 1 review moved identity out — this class's own
         * KDoc). On success, [GroupDetailUiState.Success.rotatedInvite] is dropped unconditionally —
         * never carried forward from whatever [state] held before this call — the identical
         * discipline [GroupsViewModel.refresh] applies to `justCreated`, for the identical reason
         * (E-I: a refresh, including one [removeMember] triggers, is not a moment the server hands
         * the invite code back). On failure, an already-[GroupDetailUiState.Success] screen is
         * marked [GroupDetailUiState.Success.isStale] instead of replaced by [GroupDetailUiState.Error]
         * — only a load with nothing already on screen produces that; a reload failure right after a
         * successful [removeMember] is exactly this case, since the removal itself already succeeded
         * server-side by the time this could fail.
         *
         * [preserveRotatedInvite] (round 1 review, minor 5 — "a guard belongs where the caller is
         * known" pointing at a SECOND call site): [refresh] passes `false`, dropping any rotated
         * code unconditionally, per this function's own reasoning above. [removeMember] passes
         * `true` — removing a different member has nothing to do with an invite code the owner may
         * still be reading off screen, and the round-0 shape (this function ALWAYS rebuilt
         * [GroupDetailUiState.Success] with [GroupDetailUiState.Success.rotatedInvite] defaulted to
         * `null`) silently discarded it the moment a remove's own reload landed — a real code,
         * already invalidated at the server, simply vanishing from the one screen that ever shows it.
         */
        private suspend fun reloadMembers(preserveRotatedInvite: Boolean) {
            try {
                val members = groupRepository.members(groupId)
                val previous = mutableState.value as? GroupDetailUiState.Success
                val rotatedInvite = if (preserveRotatedInvite) previous?.rotatedInvite else null
                mutableState.value = GroupDetailUiState.Success(members = members, rotatedInvite = rotatedInvite)
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
         * **Guarded to [GroupDetailUiState.Success] explicitly** (round 1 review, BLOCKING 3): a
         * call from [GroupDetailUiState.Loading]/[GroupDetailUiState.Error] used to still hit
         * `POST /invite/rotate` — genuinely rotating the server-side code — and then discard the
         * response, because there was no [GroupDetailUiState.Success] to fold it into. That is an
         * IRREVERSIBLE consequence (the previous code stops working) for a call this screen never
         * offers outside [GroupDetailUiState.Success] in the first place (the rotate button lives
         * inside `GroupDetailSuccessContent`, gated on `isOwner`, which itself requires a loaded
         * member list to compute) — the guard below makes that restriction real rather than merely
         * true-in-practice-today.
         */
        fun rotateInvite() {
            if (mutableState.value !is GroupDetailUiState.Success) return
            if (mutableActionState.value.rotating) return
            mutableActionState.value = mutableActionState.value.copy(rotating = true, rotateError = null)
            viewModelScope.launch {
                try {
                    val invite = groupRepository.rotateInvite(groupId)
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
         * `DELETE /v1/groups/{id}/members/{userId}` with the SIGNED-IN user's own id —
         * [removeMember]'s sibling, calling the identical repository method with a different id
         * (design doc §1.1). Kept as its own function, on its own [GroupDetailActionState] channel,
         * rather than `removeMember(currentUserId)`: they have different success handling
         * ([mutableLeft], not a reload) and — Global Constraints' "a guard belongs where the caller
         * is known" — the CALLER (the "Leave group" button) is what knows this is a leave, not a
         * remove; recovering that fact from the id alone downstream would be inferring it instead.
         *
         * **Reads [authRepository] directly, not the cached [currentUserId] field** (round 1 review,
         * BLOCKING 2, fixed by moving identity resolution off [state] entirely — this class's own
         * KDoc). This is what makes leaving reachable from EVERY [GroupDetailUiState], including
         * [GroupDetailUiState.Error]: [GroupDetailActionState]'s own KDoc already argued "a member
         * holding a valid reason to leave a group has every reason to do so from a screen whose OWN
         * member-list load just failed" — round 0 asserted that in words and then guarded against it
         * in code with `(state as? Success)?.currentUserId ?: return`, the literal shape
         * `GroupsActionState`'s fix rounds existed to remove. There is no such guard here now: this
         * function always attempts, and [authRepository.currentUserId] is cheap (cached) after the
         * first successful resolution and self-healing if it previously failed.
         *
         * `@Suppress("TooGenericExceptionCaught")`: the ONE function in this class that catches a
         * generic [Exception] alongside [GroupOperationException], because [authRepository]'s own
         * `currentUserId()` throws [com.anarky.showtrack.core.model.AuthFailure], not
         * [GroupOperationException] — both failure shapes render identically here
         * ([GroupFailure.Unknown]'s generic copy), since this screen has no more specific story for
         * "couldn't confirm who you are" than for any other unmapped failure.
         */
        @Suppress("TooGenericExceptionCaught")
        fun leaveGroup() {
            if (mutableActionState.value.leaving) return
            mutableActionState.value = mutableActionState.value.copy(leaving = true, leaveError = null)
            viewModelScope.launch {
                try {
                    val userId = authRepository.currentUserId()
                    groupRepository.removeMember(groupId, userId)
                    mutableActionState.value = mutableActionState.value.copy(leaving = false)
                    mutableLeft.value = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    mutableActionState.value =
                        mutableActionState.value.copy(leaving = false, leaveError = failure.failure)
                } catch (failure: Exception) {
                    mutableActionState.value =
                        mutableActionState.value.copy(leaving = false, leaveError = GroupFailure.Unknown(failure))
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
                    groupRepository.removeMember(groupId, userId)
                    reloadMembers(preserveRotatedInvite = true)
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
