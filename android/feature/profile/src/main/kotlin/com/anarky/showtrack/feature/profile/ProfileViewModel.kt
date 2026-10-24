package com.anarky.showtrack.feature.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.CurrentUser
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.feature.profile.alerts.AlertSwitch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ShowTrackProfile"

/**
 * The library-stats block (task 9b.5, decision D-F's stats half) — one of the screen's independent
 * concerns alongside alerts and sign-out (decision C-S), with its own failure channel.
 *
 * [Success.isStale] mirrors `FavoritesUiState.Success.isStale`, for the identical reason: a
 * resume's failed background refetch must not destroy numbers the user is already looking at
 * (decision C-B). `FavoritesViewModel.refresh`'s own KDoc documents the two-round bug this shape
 * exists to prevent — [ProfileViewModel.refreshStats] follows the same discipline for [statsState]
 * that it already follows for `FavoritesUiState.Success` there: [Loading] is written only when
 * nothing is on screen yet, and a failure over an already-[Success] state marks it stale instead
 * of replacing it with [Error].
 */
sealed interface LibraryStatsUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : LibraryStatsUiState

    data class Success(
        val stats: LibraryStats,
        val isStale: Boolean = false,
    ) : LibraryStatsUiState

    /** Only a failed fetch with nothing already on screen ever produces this. */
    data class Error(
        val cause: Throwable,
    ) : LibraryStatsUiState
}

/**
 * The state stays split across [alertsEnabled]/[signedOut]/[signOutError]/[statsState]/[user]
 * rather than folded into one `ProfileUiState`: they are independent concerns with independent
 * failure modes (decision C-S), and a single wrapper would force every reader to reconstruct which
 * combinations are actually reachable.
 *
 * Nothing loads in `init`: `ProfileScreen` calls [refreshStats] and [refreshUser] from a
 * `LifecycleResumeEffect`, which also fires on the first composition, so loading here too would
 * issue every GET twice on a cold start with no ordering between them.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val alerts: AlertSwitch,
        private val authRepository: AuthRepository,
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        // The Episode alerts switch as stored on the phone. Whether notifications are allowed is
        // the screen's to check (it needs an Activity), so it is not folded in here.
        val alertsEnabled: StateFlow<Boolean> =
            alerts.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

        // `false` once and never reset: this ViewModel is scoped to the NavBackStackEntry
        // and is torn down the moment ProfileScreen navigates away on `true`, so there is no second
        // sign-out to observe.
        private val mutableSignedOut = MutableStateFlow(false)
        val signedOut: StateFlow<Boolean> = mutableSignedOut.asStateFlow()

        // Set on a failed signOut() only — see its KDoc. Cleared at the start of the next attempt
        // so a stale error does not linger under a retry that is still in flight.
        private val mutableSignOutError = MutableStateFlow(false)
        val signOutError: StateFlow<Boolean> = mutableSignOutError.asStateFlow()

        // An independent channel (decision C-S) — see LibraryStatsUiState's own
        // KDoc for the isStale/Loading discipline this follows.
        private val mutableStatsState = MutableStateFlow<LibraryStatsUiState>(LibraryStatsUiState.Loading)
        val statsState: StateFlow<LibraryStatsUiState> = mutableStatsState.asStateFlow()

        // The signed-in account for the header. Null until the first load lands; a failed load keeps
        // whatever was shown before (or nothing), because the header is decoration around the
        // stats and settings, and an error banner over a username would outweigh it.
        private val mutableUser = MutableStateFlow<CurrentUser?>(null)
        val user: StateFlow<CurrentUser?> = mutableUser.asStateFlow()

        // Guards [refreshStats] against re-entrancy (task 9c.8, E-M) — `FavoritesViewModel.refreshInFlight`'s
        // identical reasoning: `ProfileScreen` wires the SAME function to both `LifecycleResumeEffect`
        // and the stats section's retry action, so a manual retry can land while a resume-triggered
        // fetch is still in flight. A private, state-shape-independent field rather than one scoped
        // inside `LibraryStatsUiState.Success`: [refreshStats] can be called while [statsState] is
        // [LibraryStatsUiState.Loading] or [LibraryStatsUiState.Error] too.
        //
        // A DROPPED re-entrant call, not a coalesced one (review finding M2, round 1) —
        // `FavoritesViewModel.refreshInFlight`'s own KDoc has the full reasoning: a second
        // [refreshStats] landing mid-flight is discarded, not queued, so a slow resume racing a
        // library change elsewhere can render a response that predates it, with no automatic
        // follow-up — the next resume is what corrects it.
        private var statsRefreshInFlight = false

        /**
         * Called once per resume by `ProfileScreen`, never from `init` (see this class's KDoc).
         *
         * [LibraryStatsUiState.Loading] is written ONLY when nothing is on screen yet
         * (`!is Success`) — carried forward from `FavoritesViewModel.refresh`'s round-1 fix: writing
         * it unconditionally would blank a populated stats block to a spinner on every single
         * resume, the exact bug that cost that task three fix rounds. On failure, an
         * already-[LibraryStatsUiState.Success] state is marked [LibraryStatsUiState.Success.isStale]
         * instead of being replaced by [LibraryStatsUiState.Error] — `FavoritesViewModel.refresh`'s
         * round-2 fix, applied here: a failed background resume must not destroy numbers the user
         * is already reading. [LibraryStatsUiState.Error] stays reachable for the case it always
         * covered: nothing usable is on screen yet.
         *
         * A stats failure never touches [alertsEnabled]/[signedOut]/[signOutError] — its own
         * `catch`, scoped to its own `mutableStatsState` (decision C-S) — so a broken
         * `/v1/library/stats` leaves alerts and sign-out fully usable, which is exactly what
         * `a failed stats load leaves the rest of the profile usable` pins.
         *
         * Guarded against re-entrancy by [statsRefreshInFlight] (task 9c.8, E-M) — see that field's
         * own KDoc: `ProfileScreen`'s stats retry action and its `LifecycleResumeEffect` both call
         * this function, and without the guard a manual retry landing while a resume fetch is still
         * in flight is last-write-wins, which can mark freshly-loaded numbers stale on top of a
         * result that had already superseded it.
         */
        @Suppress("TooGenericExceptionCaught")
        fun refreshStats() {
            if (statsRefreshInFlight) return
            statsRefreshInFlight = true
            if (mutableStatsState.value !is LibraryStatsUiState.Success) {
                mutableStatsState.value = LibraryStatsUiState.Loading
            }
            viewModelScope.launch {
                try {
                    val stats = libraryRepository.libraryStats()
                    // `.copy()` off the current Success rather than a fresh one (whole-branch fix
                    // round). Not a live defect — [LibraryStatsUiState.Success] has exactly two
                    // fields today and this call determines both — but it is the same SHAPE as the
                    // rebuild that was live in `FavoritesViewModel.refresh`, and this state can be
                    // Success at the moment of the write (a resume over an already-populated screen
                    // does not blank it). A third field added later is carried forward here instead
                    // of silently reset; `isStale = false` stays named, because a successful fetch
                    // clearing it is a decision, not a default.
                    val previous = mutableStatsState.value as? LibraryStatsUiState.Success
                    mutableStatsState.value =
                        previous?.copy(stats = stats, isStale = false) ?: LibraryStatsUiState.Success(stats)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val stillShowing = mutableStatsState.value as? LibraryStatsUiState.Success
                    mutableStatsState.value =
                        stillShowing?.copy(isStale = true) ?: LibraryStatsUiState.Error(failure)
                } finally {
                    statsRefreshInFlight = false
                }
            }
        }

        /** Reloads the header's account details. A failure leaves the last known ones in place. */
        @Suppress("TooGenericExceptionCaught")
        fun refreshUser() {
            viewModelScope.launch {
                try {
                    mutableUser.value = authRepository.currentUser()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.w(TAG, "account load failed: ${failure.javaClass.simpleName}")
                }
            }
        }

        /**
         * Turns alerts on (the screen has already made sure notifications are allowed) or off,
         * which cancels everything scheduled. A failed local write leaves the switch as it was.
         */
        @Suppress("TooGenericExceptionCaught")
        fun setAlertsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    alerts.setEnabled(enabled)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.w(TAG, "alerts switch failed: ${failure.javaClass.simpleName}")
                }
            }
        }

        /**
         * `AuthRepository.logout()` cancels this phone's episode alerts, revokes the refresh
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
         * DataStore's `edit` transaction did not commit, so the LOCAL token is NOT actually
         * cleared — navigating the user back to the login screen at that point would be a lie (a
         * relaunch would find a valid token and land them right back in the library), worse than
         * leaving them on Profile with a chance to retry. [signOutError] is what tells them that,
         * instead.
         *
         * That is not the same as "nothing happened", and this KDoc used to imply it was. By the
         * time `clear()` can even run, `AuthRepository.logout()` has already cancelled the alerts
         * and called `revoke()`, swallowing its failures (see its KDoc) — so a `clear()` failure
         * specifically leaves the user signed in locally with alerts switched off and the refresh
         * token possibly already revoked. Turning alerts back on is one tap, and a revoked
         * refresh token simply fails its next use, which is
         * exactly the terminal-refresh path `AuthEventBus`/`AuthGate` already handle. Worth
         * knowing when reading this failure, not worth guarding against — retrying [signOut] is
         * the same call either way.
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
