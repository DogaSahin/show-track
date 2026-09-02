package com.anarky.showtrack.feature.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryStats
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
 * The library-stats block (task 9b.5, decision D-F's stats half) — the screen's THIRD independent
 * concern alongside push and sign-out (decision C-S), with its own failure channel exactly like
 * those two.
 *
 * [Success.isStale] mirrors `FavoritesUiState.Success.isStale`, for the identical reason: a
 * resume's failed background refetch must not destroy numbers the user is already looking at
 * (decision C-B). `FavoritesViewModel.refresh`'s own KDoc documents the two-round bug this shape
 * exists to prevent — [ProfileViewModel.refresh] follows the same discipline for [statsState]
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
 * Push is not the only thing on this screen any more — task 9b.5 added [statsState] — but the
 * state stays split across [pushState]/[signedOut]/[signOutError]/[statsState] rather than folded
 * into one `ProfileUiState`: the four are independent concerns with independent failure modes
 * (decision C-S), and a single wrapper `data class` would force every reader to reconstruct which
 * combinations are actually reachable instead of the type system doing it.
 *
 * Re-read on [refresh] rather than observed: a distributor is installed or uninstalled by the
 * user leaving the app entirely, and `PackageManager` offers no flow. `ProfileScreen` calls
 * [refresh] from a `LifecycleResumeEffect`, which is exactly when the answer can have changed —
 * the `init` below covers only the first composition, and the ViewModel is scoped to the
 * NavBackStackEntry, so it survives the trip to the Play Store and back that the NoDistributor
 * prompt asks the user to make.
 *
 * [statsState] rides along on the SAME [refresh] rather than its own resume effect (ruling, task
 * 9b.5's brief): the library changes on OTHER screens, so a stats block that only loaded once on
 * `init` would be stale exactly when a user navigates back here to check it. The one accepted
 * consequence: unlike push's synchronous `PackageManager` read, a stats fetch is a real network
 * round trip, so [enablePush]/[disablePush] now also re-issue one by calling [refresh] — and the
 * `init` below plus `ProfileScreen`'s `LifecycleResumeEffect` firing on the very first composition
 * (the same replay `FavoritesViewModel`'s own KDoc measures) means the FIRST open fetches stats
 * twice. Both are harmless — a stats re-fetch is idempotent and the second call's [Loading] guard
 * never fires over a populated screen — not free, so worth naming rather than leaving as a silent
 * side effect of this ruling.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val distributors: DistributorSource,
        private val authRepository: AuthRepository,
        private val libraryRepository: LibraryRepository,
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

        // The screen's THIRD independent channel (decision C-S) — see LibraryStatsUiState's own
        // KDoc for the isStale/Loading discipline this follows.
        private val mutableStatsState = MutableStateFlow<LibraryStatsUiState>(LibraryStatsUiState.Loading)
        val statsState: StateFlow<LibraryStatsUiState> = mutableStatsState.asStateFlow()

        init {
            refresh()
        }

        /**
         * Push's half is unchanged from before task 9b.5: a synchronous `PackageManager` read,
         * still not wrapped in `viewModelScope.launch`.
         *
         * The stats half is new and asynchronous — see this class's own KDoc for why it lives here
         * rather than behind its own resume effect, and for the accepted duplicate-fetch-on-first-
         * open consequence. [LibraryStatsUiState.Loading] is written ONLY when nothing is on screen
         * yet (`!is Success`) — carried forward from `FavoritesViewModel.refresh`'s round-1 fix:
         * writing it unconditionally would blank a populated stats block to a spinner on every
         * single resume, the exact bug that cost that task three fix rounds. On failure, an
         * already-[LibraryStatsUiState.Success] state is marked [LibraryStatsUiState.Success.isStale]
         * instead of being replaced by [LibraryStatsUiState.Error] — `FavoritesViewModel.refresh`'s
         * round-2 fix, applied here: a failed background resume must not destroy numbers the user
         * is already reading. [LibraryStatsUiState.Error] stays reachable for the case it always
         * covered: nothing usable is on screen yet.
         *
         * A stats failure never touches [pushState]/[signedOut]/[signOutError] — its own `catch`,
         * scoped to its own `mutableStatsState` (decision C-S) — so a broken `/v1/library/stats`
         * leaves push opt-in and sign-out fully usable, which is exactly what
         * `a failed stats load leaves the rest of the profile usable` pins.
         */
        @Suppress("TooGenericExceptionCaught")
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

            if (mutableStatsState.value !is LibraryStatsUiState.Success) {
                mutableStatsState.value = LibraryStatsUiState.Loading
            }
            viewModelScope.launch {
                try {
                    val stats = libraryRepository.libraryStats()
                    mutableStatsState.value = LibraryStatsUiState.Success(stats)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val stillShowing = mutableStatsState.value as? LibraryStatsUiState.Success
                    mutableStatsState.value =
                        stillShowing?.copy(isStale = true) ?: LibraryStatsUiState.Error(failure)
                }
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
         * DataStore's `edit` transaction did not commit, so the LOCAL token is NOT actually
         * cleared — navigating the user back to the login screen at that point would be a lie (a
         * relaunch would find a valid token and land them right back in the library), worse than
         * leaving them on Profile with a chance to retry. [signOutError] is what tells them that,
         * instead.
         *
         * That is not the same as "nothing happened", and this KDoc used to imply it was. By the
         * time `clear()` can even run, `AuthRepository.logout()`'s `detachPush()` and `revoke()`
         * have already executed and swallowed their own failures (see its KDoc) — so a `clear()`
         * failure specifically leaves the user signed in locally with the server-side push target
         * already deleted and the refresh token possibly already revoked. Both recover on their
         * own without more code here: the next successful login re-registers push
         * (`registerForPush()`), and a revoked refresh token simply fails its next use, which is
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
