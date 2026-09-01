package com.anarky.showtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The startup half of the auth gate. [AuthGate] is REACTIVE — it collects `AuthEvent.LoggedOut`,
 * which is emitted when a refresh fails. A logged-out cold start has no token to fail a refresh
 * with, so it emits nothing, and without this the app opens on an empty Library and stays there.
 * Both halves are needed: this one cannot see an expiry mid-session, and that one cannot see a
 * cold start.
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        auth: AuthRepository,
    ) : ViewModel() {
        val start: StateFlow<AppStart> =
            flow { emit(if (auth.hasSession()) AppStart.Library else AppStart.Auth) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppStart.Undecided)
    }

sealed interface AppStart {
    data object Undecided : AppStart

    data object Auth : AppStart

    data object Library : AppStart
}
