package com.anarky.showtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.group.ActiveGroupStore
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the group switcher's own state (task 9c.5, decisions E-C/E-D/E-K) — Activity-scoped, the
 * same lifetime as [AppViewModel], resolved via `hiltViewModel()` above any `NavBackStackEntry`
 * scope (`ShowTrackNavHost`'s own precedent). Nothing in `:feature:feed`/`:feature:groups` ever
 * references this CLASS — E-C's whole point ("never read from a singleton inside a feature
 * module") — [state] crosses that boundary as a plain `StateFlow<ActiveGroupState>` constructor/
 * parameter value instead: `ShowTrackNavHost` reads it here and hands it into `feedEntry`/
 * `groupsEntry` as an ordinary parameter, `authEvents: Flow<AuthEvent>`'s identical shape.
 *
 * [state] is a plain [MutableStateFlow], not `stateIn(WhileSubscribed(5_000))` (decision C-U):
 * neither [GroupRepository.groups] nor [ActiveGroupStore.activeGroupId] is Room-backed, and this
 * class also outlives any single screen's subscription (Activity-scoped), so there is no "nobody is
 * collecting" window for `WhileSubscribed` to economise on — `AppViewModel.start`'s identical shape.
 *
 * **No `refresh()` call in `init` (fix round 1, BLOCKING B4).** This class used to fetch eagerly
 * the moment it was constructed — which, resolved as a DEFAULT PARAMETER on `ShowTrackNavHost`
 * (`activeGroupViewModel: ActiveGroupViewModel = hiltViewModel()`), happens before `ShowTrackNavHost`
 * even reads `AppViewModel.start`, i.e. for `AppStart.Undecided`/`AppStart.Auth` too — an
 * authenticated `GET /v1/groups` fired at the LOGIN SCREEN on every cold start. The actual fetch is
 * now driven entirely by `ShowTrackApp`'s own `LaunchedEffect`, keyed on the current destination —
 * `GroupsViewModel`'s own resume-driven-load precedent one level up (this class has no
 * `NavBackStackEntry` of its own to hang a `LifecycleResumeEffect` off, so `:app` supplies the
 * equivalent trigger from the one place that does know which tab is current).
 *
 * **Which destinations trigger it (whole-branch fix round, BLOCKING 2).** Originally only Feed and
 * Groups did, which was wrong the moment task 9c.6 gave `:feature:detail` a group section: the
 * ordinary cold-start path is Library (the start destination) -> tap a title -> Detail, and on that
 * path no fetch was ever issued, so [state] stayed [ActiveGroupState.Loading] for the life of the
 * Activity and Detail's whole group section and propose control rendered nothing — indistinguishable
 * from an account in no groups, and identical for the `showtrack://detail/<id>` push deep link,
 * which cannot pass through a tab at all. [activeGroupActionFor] now answers `Refresh` for ANY
 * authenticated destination while [hasRequestedGroups] is `false`, keeping Feed and Groups as the
 * two explicit re-refresh points; see [hasRequestedGroups] for why that flag rather than
 * [hasLoadedOnce].
 */
@HiltViewModel
class ActiveGroupViewModel
    @Inject
    constructor(
        private val groupRepository: GroupRepository,
        private val activeGroupStore: ActiveGroupStore,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ActiveGroupState>(ActiveGroupState.Loading)
        val state: StateFlow<ActiveGroupState> = mutableState.asStateFlow()

        // The last groups() response, and whether one has ever landed — recompute()'s own inputs,
        // alongside storedGroupId. hasLoadedOnce is what keeps a store-only change (the collector
        // below) from writing a premature Success(emptyList(), null) over Loading before the very
        // first fetch has even resolved (BLOCKING B3): without it, ActiveGroupStore's initial
        // emission at cold start — which fires before refresh() has ever been called — would look
        // exactly like "loaded, and genuinely empty".
        private var lastGroups: List<Group> = emptyList()
        private var hasLoadedOnce = false
        private var storedGroupId: String? = null

        /**
         * Whether a groups fetch has been REQUESTED since construction or the last [reset] — set
         * synchronously by [refresh] before it launches, cleared by [reset]. `ShowTrackApp` reads it
         * through [activeGroupActionFor] to decide whether an ordinary authenticated destination
         * should trigger the one load-per-session this class needs (whole-branch fix round,
         * BLOCKING 2).
         *
         * NOT [hasLoadedOnce], which is the near neighbour and the wrong flag: that one is set only
         * when a response actually LANDS, so it stays `false` for the whole round trip, and a
         * `Library -> Detail` navigation inside that window would read it as "still not requested"
         * and fire a second `GET /v1/groups`. Since `ShowTrackApp`'s effect re-evaluates on every
         * destination change, that is a fetch per screen opened until the first one resolves —
         * exactly the per-open cost the load-once shape exists to avoid.
         *
         * Consequence, stated rather than hidden: a FAILED first fetch leaves this `true`, so
         * navigating around does not retry it. That is deliberate and matches what the two explicit
         * refresh points already do — Feed and Groups re-fire [refresh] on every arrival, and
         * `FeedScreen` offers [ActiveGroupState.Error] a real retry button. A sign-out clears it via
         * [reset], so the next account loads from scratch.
         */
        internal var hasRequestedGroups = false
            private set

        // Bumped on every refresh() call, and checked before either of its two writes below —
        // FeedViewModel's own `generation` shape, applied to a single-shot fetch instead of a
        // paginator (fix round 1, smaller item 2): ShowTrackApp's LaunchedEffect re-fires refresh()
        // on every arrival at Feed or Groups, so rapid tab switching can have an OLDER groups()
        // response still in flight when a NEWER one is requested; without this guard the older
        // response landing second would briefly resurrect a group the account just left, or drop
        // one it just created.
        private var refreshGeneration = 0

        init {
            // Collects for this instance's entire (Activity-scoped) lifetime, AppViewModel's own
            // init-block shape for its session check: the store's Flow is how a switch this class's
            // own selectGroup makes — or a reset() clearing it — reaches back here.
            viewModelScope.launch {
                activeGroupStore.activeGroupId.collect { stored ->
                    storedGroupId = stored
                    if (hasLoadedOnce) recompute()
                }
            }
        }

        /**
         * Re-fetches the group list. Called by `ShowTrackApp` on the first visit to Feed or Groups
         * per session (this class's own KDoc) — never from `init`.
         *
         * A failure only replaces an already-[ActiveGroupState.Success] screen when this is the
         * FIRST fetch ever (fix round 1, BLOCKING B3's other half — the settled refresh shape,
         * `FeedViewModel.reload`'s identical reasoning): a retry failing after a success keeps
         * showing what is already resolved rather than erroring a working switcher away. There is
         * no dedicated "stale" marking here (unlike `FeedUiState.Success.isStale`) because nothing
         * downstream renders one yet — a documented simplification, not an oversight.
         */
        fun refresh() {
            hasRequestedGroups = true
            val myGeneration = ++refreshGeneration
            viewModelScope.launch {
                try {
                    val fetched = groupRepository.groups()
                    if (myGeneration != refreshGeneration) return@launch
                    lastGroups = fetched
                    hasLoadedOnce = true
                    recompute()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    if (myGeneration != refreshGeneration) return@launch
                    if (mutableState.value !is ActiveGroupState.Success) {
                        mutableState.value = ActiveGroupState.Error(failure.failure)
                    }
                }
            }
        }

        /** [GroupSwitcher][com.anarky.showtrack.core.designsystem.component.GroupSwitcher]'s own callback. */
        fun selectGroup(groupId: String) {
            viewModelScope.launch { activeGroupStore.setActiveGroup(groupId) }
        }

        /**
         * Clears every trace of the previous account (fix round 1, BLOCKING B4). This ViewModel is
         * Activity-scoped, and a logout is a `navigate`, not an Activity recreation — the
         * `ViewModelStore` survives it untouched — so without this, account A signs out, account B
         * signs in inside the SAME process, and B's Feed/switcher would show A's group names and
         * fetch A's feed for a group B was never in. `AuthRepositoryImpl.login`'s own `cachedUserId`
         * comment already names this exact failure shape for a different field.
         *
         * Called by `ShowTrackApp` whenever the current destination becomes `AuthRoute` — the one
         * choke point BOTH a session-expiry logout (`AuthGate`'s reactive collector) and a
         * user-initiated sign-out (`ProfileNavigation`'s door) already route through
         * (`navigateToAuthClearingStack`, `ShowTrackNavHost.kt`), so this needs no `AuthEvent`
         * dependency of its own — a cold, signed-out start also lands on `AuthRoute` and calls this
         * harmlessly (nothing to clear yet).
         *
         * **`refreshGeneration++` here too (fix round 2, BLOCKING F1).** [refresh]'s own guard
         * exists for exactly this shape — a fetch still in flight when the subject it was launched
         * for stops being current — and [reset] IS a subject change (the account), the same as a
         * group switch is. Without this bump, account A's `refresh()` left in flight when A signs
         * out lands AFTER [reset] has already cleared everything, and [refresh]'s own generation
         * check (`myGeneration != refreshGeneration`) reads as still current — because nothing here
         * had changed `refreshGeneration` — so A's stale response republishes A's groups over
         * `Loading`, and [recompute]'s own write-back (this class's own KDoc, smaller item 1) then
         * writes A's group id back into the store this function just cleared. Measured: a gated
         * probe reproduces exactly that `Success` where `Loading` was expected — see
         * `ActiveGroupViewModelTest`'s own test for the shape.
         */
        fun reset() {
            hasLoadedOnce = false
            hasRequestedGroups = false
            lastGroups = emptyList()
            storedGroupId = null
            refreshGeneration++
            mutableState.value = ActiveGroupState.Loading
            viewModelScope.launch { activeGroupStore.setActiveGroup(null) }
        }

        /**
         * E-K resolved here. The `else` branch deliberately serves BOTH "nothing stored" and "the
         * stored id names a group this account left" — the same fallback, because both are the
         * identical "no valid stored selection" fact from this function's point of view (E-K's own
         * reasoning: being removed from a group is normal, not a fault).
         *
         * The resolved id is written BACK to [activeGroupStore] whenever it differs from what was
         * stored (fix round 1, smaller item 1): without this, a fallback selection and the stored
         * value diverge permanently — leave group B (the store still says "B"), the fallback
         * silently picks A instead, and rejoining B months later would jump straight back to it,
         * because the store never learned the account had moved on from B in the meantime.
         *
         * [selectGroup] carries no defensive check that its argument names a real group, and that
         * is correct, not merely convenient (fix round 1, smaller item 4 — correcting an earlier
         * wrong justification here that called it "safe because the switcher is the only caller",
         * which is exactly the reasoning that made `FeedViewModel.selectGroup` unguarded until this
         * task made it reachable): a bogus write there just becomes a [storedGroupId] this
         * function's `else` branch cannot match, and the resolved [ActiveGroupState.Success.activeGroupId]
         * falls back to [lastGroups]'s own first entry exactly as it would for a group that was
         * left. The resolved id can never observably take a value [lastGroups] does not contain —
         * that guarantee lives HERE, in the fallback, not in a check on [selectGroup]'s input.
         *
         * **What that guarantee is worth depends on [lastGroups] being the list the user was
         * OFFERED, and until the whole-branch fix round it was not (BLOCKING 3).** `GroupsScreen`
         * rendered its switcher tabs from `GroupsViewModel`'s independently-refreshed list, which a
         * create or join appends to immediately without navigating — and nothing re-fires [refresh]
         * for a screen the user never left. So a genuinely-joined group appeared as a tappable tab,
         * [selectGroup] wrote it, and the `else` branch below then discarded it as "a group you are
         * no longer in": the tab snapped back within a frame and the write-back above overwrote the
         * user's choice in [activeGroupStore] with the old id. The code did exactly what this
         * comment said; the comment never asked whether [lastGroups] was the right list.
         *
         * It is now, by construction: every switcher on the app renders from
         * [ActiveGroupState.Success.groups] — the value this function publishes from [lastGroups] —
         * and `GroupsScreen` additionally reports a create/join back here (`onGroupsChanged` ->
         * [refresh]) so this list learns about a membership change made on a screen that does not
         * navigate. The fallback therefore now only ever fires for what it was written for: nothing
         * stored, or a stored id naming a group the account has genuinely left.
         */
        private fun recompute() {
            val resolved =
                when {
                    lastGroups.isEmpty() -> null
                    storedGroupId != null && lastGroups.any { group -> group.id == storedGroupId } -> storedGroupId
                    else -> lastGroups.first().id
                }
            if (resolved != storedGroupId) {
                storedGroupId = resolved
                viewModelScope.launch { activeGroupStore.setActiveGroup(resolved) }
            }
            mutableState.value = ActiveGroupState.Success(groups = lastGroups, activeGroupId = resolved)
        }
    }
