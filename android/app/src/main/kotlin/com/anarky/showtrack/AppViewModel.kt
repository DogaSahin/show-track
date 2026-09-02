package com.anarky.showtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The startup half of the auth gate. [AuthGate] is REACTIVE — it collects `AuthEvent.LoggedOut`,
 * which is emitted when a refresh fails. A logged-out cold start has no token to fail a refresh
 * with, so it emits nothing, and without this the app opens on an empty Library and stays there.
 * Both halves are needed: this one cannot see an expiry mid-session, and that one cannot see a
 * cold start.
 *
 * [start] is a plain [MutableStateFlow], not `.stateIn(SharingStarted.Eagerly, ...)` over a
 * one-shot flow — decision C-U ("a plain MutableStateFlow where a ViewModel holds no Room-backed
 * upstream; never Eagerly") applies here as much as anywhere else, and the one-shot-emission shape
 * this replaced is also what made [start] unable to represent anything past the FIRST session
 * check: a review round (task 9b.0, finding 1) caught that mutating the already-built `NavGraph`'s
 * `startDestinationId` after a login — the fix that shape forced — does not survive an Activity
 * recreation, since `NavGraph` state lives in the composition, not in this ViewModel, and gets
 * rebuilt from whatever `start` says on the next composition. [markSignedIn] is what [start] needs
 * to be mutable FOR: the graph's *declared* `startDestination` (`ShowTrackNavHost.startDestinationFor`)
 * is what has to change, not a graph already built.
 *
 * Round 1 (task 9b.6 fix round): [AppStart.Onboarding] is what makes the SAME mechanism serve the
 * AniList import screen. A version of this fix that navigated to `ImportRoute` and then called
 * [markSignedIn] discovered the mechanism works AGAINST a caller who fights it: `markSignedIn`
 * promoting `Auth` to `Library` in the same frame as an explicit navigate to `ImportRoute` still
 * re-supplies a graph whose declared start is `LibraryRoute`, and `NavController.setGraph`'s
 * inequality branch resets the back stack to THAT — wiping the navigation that had just happened,
 * regardless of which of the two calls ran first (both land in the same recomposition). The fix
 * is not to fight the reset but to make `start` agree with where the navigate is actually going:
 * [Onboarding] is a THIRD decided value precisely so the graph's declared start destination and
 * the imperative navigate converge on `ImportRoute` together, the same way `Auth` promoting to
 * `Library` already converges with `navigateToLibraryClearingAuth()` — see `ShowTrackNavHost`'s
 * KDoc for the mechanism this reuses rather than reinvents.
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
    ) : ViewModel() {
        private val mutableStart = MutableStateFlow<AppStart>(AppStart.Undecided)
        val start: StateFlow<AppStart> = mutableStart.asStateFlow()

        init {
            viewModelScope.launch {
                mutableStart.value = if (auth.hasSession()) AppStart.Library else AppStart.Auth
            }
        }

        /**
         * Promotes an `Auth`-started session once authentication succeeds — called from
         * `ShowTrackNavHost`'s routing table at the exact choke point `navigateToLibraryClearingAuth`/
         * `navigateToImportClearingAuth` already is.
         *
         * [isNewAccount] decides WHICH decided value: [AppStart.Onboarding] for a fresh
         * registration (offering the AniList import screen), [AppStart.Library] for everything
         * else — an ordinary login, AND finishing onboarding itself. That second case is why this
         * is one function taking a parameter rather than two named ones: `routeShowTrackNavigation`'s
         * `LibraryRoute` branch calls this with `isNewAccount = false` both when a login completes
         * (`start` was `Auth`) and when the import screen's skip/Done action finishes onboarding
         * (`start` was `Onboarding`) — in both cases the destination this call promotes TOWARD is
         * `Library`, and the caller does not need a second name for "not new, and also not
         * currently mid-onboarding" to say so.
         *
         * One-way and idempotent, deliberately: [start] never reverts to [AppStart.Auth], and never
         * moves from [AppStart.Library] back to [AppStart.Onboarding] — onboarding is offered once,
         * at the moment of registration, never re-offered to an already-promoted session (Profile's
         * own door to `ImportRoute` calls this function ZERO times; see `ShowTrackNavHost`'s
         * `ImportRoute` branch). A runtime logout is handled entirely by navigation
         * (`navigateToAuthClearingStack`), never by moving this value backward — see
         * [ShowTrackNavHost]'s KDoc for why the graph's *declared* start destination is meant to
         * describe "how far this session has been promoted", not "is the user currently signed in
         * this instant". Calling this with a value [start] already holds (a second login after a
         * mid-session logout, or a second call reaching `Library` from `Library`) is a same-value
         * `StateFlow` write — no-op, no recomposition.
         */
        fun markSignedIn(isNewAccount: Boolean) {
            mutableStart.value = if (isNewAccount) AppStart.Onboarding else AppStart.Library
        }
    }

sealed interface AppStart {
    data object Undecided : AppStart

    data object Auth : AppStart

    data object Library : AppStart

    /**
     * A fresh registration, signed in but not yet past the AniList import offer (task 9b.6).
     * [ShowTrackNavHost.startDestinationFor] maps this to `ImportRoute` — see [AppViewModel.markSignedIn]'s
     * KDoc for why this needed to be a THIRD decided value rather than a flag riding along with
     * [Library].
     */
    data object Onboarding : AppStart
}
