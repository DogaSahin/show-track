package com.anarky.showtrack.feature.profile

import androidx.lifecycle.ViewModel
import com.anarky.showtrack.feature.profile.push.DistributorSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * What the profile screen shows about push, as a closed set of states.
 *
 * A sealed hierarchy rather than a handful of booleans, because the states are mutually exclusive
 * and the compiler should say so: `distributorInstalled = false, registered = true` is
 * representable with booleans and means nothing.
 */
sealed interface PushState {
    /** No distributor app is installed. The one state that must never be silent. */
    data object NoDistributor : PushState

    /** At least one distributor is installed, but this app has not registered with one. */
    data class Available(
        val distributors: List<String>,
    ) : PushState

    /** Registered with [distributor]. Notifications should arrive. */
    data class Registered(
        val distributor: String,
    ) : PushState
}

/**
 * Push is the only thing on this screen so far, which is why the state is `PushState` and not a
 * `ProfileUiState` wrapping it — a wrapper with one field is a rename waiting to happen.
 *
 * Re-read on [refresh] rather than observed: a distributor is installed or uninstalled by the
 * user leaving the app entirely, and `PackageManager` offers no flow. `ProfileScreen` calls
 * [refresh] from a `LifecycleResumeEffect`, which is exactly when the answer can have changed —
 * the `init` below covers only the first composition, and the ViewModel is scoped to the
 * NavBackStackEntry, so it survives the trip to the Play Store and back that the NoDistributor
 * prompt asks the user to make.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val distributors: DistributorSource,
    ) : ViewModel() {
        private val mutablePushState = MutableStateFlow<PushState>(PushState.NoDistributor)
        val pushState: StateFlow<PushState> = mutablePushState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            val installed = distributors.available()
            mutablePushState.value =
                when {
                    installed.isEmpty() -> PushState.NoDistributor
                    // `selected` is only trusted when it is STILL installed. A distributor the
                    // user uninstalled leaves the saved choice behind, and reporting Registered
                    // for an app that is gone is the silent failure this whole state machine
                    // exists to prevent.
                    else ->
                        distributors.selected()?.takeIf { it in installed }?.let(PushState::Registered)
                            ?: PushState.Available(installed)
                }
        }

        /** Chooses a distributor. `onNewEndpoint` does the rest, asynchronously and out of process. */
        fun enablePush(distributor: String) {
            distributors.register(distributor)
            refresh()
        }

        fun disablePush() {
            distributors.unregister()
            refresh()
        }
    }
