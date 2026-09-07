package com.anarky.showtrack.feature.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The four text fields' local draft state, held in one place so `AuthFieldInputs` and
 * `AuthScreen`'s submit lambda share one copy rather than hoisting a draft the user is still
 * typing all the way up into [AuthUiState]. `internal` rather than `private` only because those
 * two live in different files; nothing outside this module sees it.
 */
internal class AuthFormFields {
    var username by mutableStateOf("")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var inviteCode by mutableStateOf("")
}
