package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.model.ImportSummary

/**
 * Each case names a different next action, `AuthError`'s own reasoning (`:feature:auth`, decision
 * C-L) applied here: [ProfileNotPublic] is the one case that must never guess which of "no such
 * AniList user" and "that user's list is private" actually happened — the server itself cannot
 * tell (`ImportFailure.ListNotPublic`'s own KDoc) — so its copy has to cover both honestly.
 */
sealed interface ImportError {
    data object ProfileNotPublic : ImportError

    data object InvalidUsername : ImportError

    data object UpstreamUnavailable : ImportError

    data object Offline : ImportError

    data object Unknown : ImportError
}

/**
 * [Form] and [Success], the same two-case shape [com.anarky.showtrack.feature.auth.AuthUiState]
 * uses for an identical reason: this screen is one form with one terminal outcome, not a set of
 * independent concerns the way [ProfileViewModel]'s push/stats/sign-out channels are (decision
 * C-S does not apply to a single sequential action).
 */
sealed interface ImportUiState {
    data class Form(
        val submitting: Boolean = false,
        val error: ImportError? = null,
    ) : ImportUiState

    /** Terminal. [summary] carries `truncated`, which the screen must render distinctly — see [ImportScreen]. */
    data class Success(
        val summary: ImportSummary,
    ) : ImportUiState
}
