package com.anarky.showtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.group.ActiveGroupStore
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.Group
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the group switcher's own state (task 9c.5, decisions E-C/E-D/E-K) — Activity-scoped, the
 * same lifetime as [AppViewModel], resolved via `hiltViewModel()` above any `NavBackStackEntry`
 * scope (`ShowTrackNavHost`'s own precedent). Nothing in `:feature:feed`/`:feature:groups` ever
 * references this class — E-C's whole point ("never read from a singleton inside a feature
 * module") — its two derived flows below cross that boundary as plain constructor/parameter values
 * instead: `ShowTrackNavHost` reads them here and hands them into `feedEntry`/`groupsEntry` as
 * ordinary `StateFlow` parameters, `authEvents: Flow<AuthEvent>`'s identical shape.
 *
 * [groups]/[activeGroupId] are plain [MutableStateFlow]s, not `stateIn(WhileSubscribed(5_000))`
 * (decision C-U): neither has a Room-backed upstream — [GroupRepository.groups] is a one-shot
 * suspend call this class drives itself ([GroupsViewModel]'s identical reasoning), and
 * [ActiveGroupStore.activeGroupId] is DataStore-, not Room-, backed. This class also outlives any
 * single screen's subscription (Activity-scoped), so there is no "nobody is collecting" window for
 * `WhileSubscribed` to economise on in the first place — `AppViewModel.start`'s identical shape.
 *
 * **E-K resolved in [recompute], every time either input changes:** no groups → `null`; the stored
 * id names a group that is still present → keep it; otherwise (nothing stored, OR the stored id
 * names a group this account is no longer in) → the FIRST group in [groups], in whatever order
 * [GroupRepository.groups] returned it — the server's own `created_at ASC` ordering, never
 * re-sorted here. That one `else` branch is deliberately what serves BOTH "nothing stored yet" and
 * "the stored group was left" — being removed from a group is a normal event (E-K's own reasoning)
 * and must resolve exactly like a fresh install with nothing stored at all, not like a fault.
 */
@HiltViewModel
class ActiveGroupViewModel
    @Inject
    constructor(
        private val groupRepository: GroupRepository,
        private val activeGroupStore: ActiveGroupStore,
    ) : ViewModel() {
        private val mutableGroups = MutableStateFlow<List<Group>>(emptyList())
        val groups: StateFlow<List<Group>> = mutableGroups.asStateFlow()

        private val mutableActiveGroupId = MutableStateFlow<String?>(null)
        val activeGroupId: StateFlow<String?> = mutableActiveGroupId.asStateFlow()

        // The last value read off ActiveGroupStore.activeGroupId — recompute()'s other input,
        // alongside mutableGroups.value. Not exposed on its own: nothing outside this class cares
        // about the STORED id, only the RESOLVED one activeGroupId already publishes.
        private var storedGroupId: String? = null

        init {
            // Collects for this instance's entire (Activity-scoped) lifetime, AppViewModel's own
            // init-block shape for its session check: the store's Flow is how a switch this class's
            // own selectGroup makes reaches back here (and, structurally, how any future second
            // writer would too), rather than this class reading its own write back out of a local
            // variable.
            viewModelScope.launch {
                activeGroupStore.activeGroupId.collect { stored ->
                    storedGroupId = stored
                    recompute()
                }
            }
            refresh()
        }

        /**
         * Re-fetches the group list. Called once from [init] (so a cold start resolves an active
         * group before the user ever reaches Feed or Groups), and again by `ShowTrackApp` whenever
         * the current destination becomes the Feed or Groups tab — this class has no
         * `NavBackStackEntry` of its own to hang a `LifecycleResumeEffect` off, so `:app` triggers
         * the resume-shaped reload from the one place that does know which tab is current.
         *
         * A transient failure here is swallowed, not surfaced: this class has no error channel of
         * its own (every consuming screen already has one for ITS OWN fetch), and the settled
         * refresh shape's usual "mark stale" has no meaning for a background list nothing renders
         * directly — keeping the last-known [groups] is strictly better than blanking the switcher
         * or the resolved [activeGroupId] out from under whatever is currently on screen.
         */
        fun refresh() {
            viewModelScope.launch {
                val fetched = runCatching { groupRepository.groups() }.getOrNull() ?: return@launch
                mutableGroups.value = fetched
                recompute()
            }
        }

        /** [GroupSwitcher][com.anarky.showtrack.core.designsystem.component.GroupSwitcher]'s own callback. */
        fun selectGroup(groupId: String) {
            viewModelScope.launch { activeGroupStore.setActiveGroup(groupId) }
        }

        private fun recompute() {
            val currentGroups = mutableGroups.value
            val stored = storedGroupId
            mutableActiveGroupId.value =
                when {
                    currentGroups.isEmpty() -> null
                    stored != null && currentGroups.any { group -> group.id == stored } -> stored
                    else -> currentGroups.first().id
                }
        }
    }
