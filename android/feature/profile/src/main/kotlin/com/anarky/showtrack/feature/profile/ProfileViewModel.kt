package com.anarky.showtrack.feature.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.feature.profile.push.DistributorSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ShowTrackProfile"

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
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val mutablePushState = MutableStateFlow<PushState>(PushState.NoDistributor)
        val pushState: StateFlow<PushState> = mutablePushState.asStateFlow()

        // Separate from PushState on purpose, not a third field folded into it: PushState is
        // "what push looks like right now" and sign-out is not a fact about push at all — folding
        // it in would force every existing `when` over PushState to grow a branch that means
        // nothing. `false` once and never reset: this ViewModel is scoped to the NavBackStackEntry
        // and is torn down the moment ProfileScreen navigates away on `true`, so there is no second
        // sign-out to observe.
        private val mutableSignedOut = MutableStateFlow(false)
        val signedOut: StateFlow<Boolean> = mutableSignedOut.asStateFlow()

        // Set on a failed signOut() only — see its KDoc. Cleared at the start of the next attempt
        // so a stale error does not linger under a retry that is still in flight.
        private val mutableSignOutError = MutableStateFlow(false)
        val signOutError: StateFlow<Boolean> = mutableSignOutError.asStateFlow()

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

        /**
         * `AuthRepository.logout()` deletes the server-side push target, revokes the refresh
         * token, and clears the local session — but it does NOT emit `AuthEvent.LoggedOut`. That
         * event is `AuthEventBus`'s signal for a token REFRESH failing (see
         * `TokenRefreshAuthenticator`), which is a different situation from a user tapping "sign
         * out" with a perfectly valid session. Because of that, `:app`'s reactive `AuthGate` never
         * fires for this path — [signedOut] is what `ProfileScreen` watches instead, to navigate
         * back to auth explicitly rather than relying on a gate that was never going to open.
         *
         * Guarded the way `AuthViewModel.submit` and `LibraryViewModel.guard` are: `logout()` can
         * throw — `tokenStore.tokens()`/`tokenStore.clear()` sit outside its own internal
         * try/catches, and a corrupt or unwritable DataStore throws `IOException` from `clear()`.
         * `viewModelScope` carries no `CoroutineExceptionHandler`, so an unguarded throw here would
         * escape to the thread's default handler and kill the process — silently, on a tap that
         * looks like nothing more than "sign out".
         *
         * [signedOut] is flipped only on SUCCESS, not in a `finally`: a thrown `clear()` means
         * DataStore's `edit` transaction did not commit, so the session was NOT actually cleared —
         * navigating the user back to the login screen at that point would be a lie (a relaunch
         * would find a valid token and land them right back in the library), worse than leaving
         * them on Profile with a chance to retry. [signOutError] is what tells them that, instead.
         */
        @Suppress("TooGenericExceptionCaught")
        fun signOut() {
            mutableSignOutError.value = false
            viewModelScope.launch {
                try {
                    authRepository.logout()
                    mutableSignedOut.value = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.w(TAG, "sign-out failed: ${failure.javaClass.simpleName}")
                    mutableSignOutError.value = true
                }
            }
        }
    }
