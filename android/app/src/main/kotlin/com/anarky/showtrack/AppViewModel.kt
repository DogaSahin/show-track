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
         * Promotes an `Auth`-started session to `Library` once login succeeds — called from
         * `ShowTrackNavHost`'s routing table at the exact choke point `navigateToLibraryClearingAuth`
         * already is: `AppDestination.kt`'s own comment records that navigating TO `LibraryRoute`
         * through that table only ever happens once, from a successful login/register.
         *
         * One-way and idempotent, deliberately: [start] never reverts to [AppStart.Auth]. A runtime
         * logout is handled entirely by navigation (`navigateToAuthClearingStack`), never by moving
         * this value backward — see [ShowTrackNavHost]'s KDoc for why the graph's *declared* start
         * destination is meant to describe "has this session ever been promoted to signed-in", not
         * "is the user currently signed in this instant". Calling this when [start] already reads
         * [AppStart.Library] (a second login after a mid-session logout) is a same-value
         * `StateFlow` write — no-op, no recomposition.
         */
        fun markSignedIn() {
            mutableStart.value = AppStart.Library
        }
    }

sealed interface AppStart {
    data object Undecided : AppStart

    data object Auth : AppStart

    data object Library : AppStart
}
