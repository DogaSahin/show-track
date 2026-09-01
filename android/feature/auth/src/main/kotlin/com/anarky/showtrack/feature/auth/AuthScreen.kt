package com.anarky.showtrack.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.ErrorState

private const val MIN_PASSWORD_LENGTH = 8

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen` uses.
 *
 * `onAuthenticated` fires exactly once per successful login/register, keyed on [AuthUiState] so
 * the effect does not re-fire on an unrelated recomposition (e.g. a config change) while already
 * `Authenticated`. `AuthNavigation` turns it into `onNavigate(LibraryRoute)`, which
 * `ShowTrackNavHost` resolves to a `popUpTo<AuthRoute>` navigation so Back cannot return here.
 */
@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is AuthUiState.Authenticated) onAuthenticated()
    }
    // Authenticated is a transient state on this screen: the LaunchedEffect above starts
    // navigating away on the same composition it appears in. Falling back to a submitting form
    // avoids a one-frame flash of an empty/default form while that navigation is in flight.
    val form = state as? AuthUiState.Form ?: AuthUiState.Form(submitting = true)
    AuthScreen(
        state = form,
        onModeChange = viewModel::setMode,
        onLogin = viewModel::submitLogin,
        onRegister = viewModel::submitRegister,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test without a graph or a
 * ViewModel — `LibraryScreen`'s pattern, one screen later.
 *
 * Field contents (username/email/password/inviteCode) are local UI state on purpose: the
 * ViewModel's `submitLogin`/`submitRegister` take raw strings directly rather than reading them
 * from `AuthUiState`, so nothing upstream needs to own a draft the user is still typing.
 */
@Composable
internal fun AuthScreen(
    state: AuthUiState.Form,
    onModeChange: (AuthMode) -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (username: String, email: String, password: String, inviteCode: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = remember { AuthFormFields() }
    var validationError by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(space = 12.dp),
    ) {
        AuthModeToggle(mode = state.mode, onModeChange = onModeChange)
        AuthFieldInputs(mode = state.mode, fields = fields)

        validationError?.let { messageRes ->
            Text(text = stringResource(messageRes), color = MaterialTheme.colorScheme.error)
        }

        // ErrorState's copy is chosen entirely by the AuthError case, never by rendering
        // `failure.message` — AuthFailure derives its Exception message from `cause.toString()`,
        // which would print a Retrofit/OkHttp class name onto a login screen.
        state.error?.let { error ->
            ErrorState(
                message = stringResource(error.messageRes()),
                // "Retry" here means "clear this error and let me try again" — calling
                // setMode(state.mode) rebuilds a fresh Form with error = null without
                // resubmitting stale credentials the user has not had a chance to correct.
                onRetry = { onModeChange(state.mode) },
            )
        }

        AuthSubmitButton(
            state = state,
            fields = fields,
            onValidationError = { validationError = it },
            onLogin = onLogin,
            onRegister = onRegister,
        )
    }
}

/** The four text fields' local draft state, held in one place so the composables below share it. */
private class AuthFormFields {
    var username by mutableStateOf("")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var inviteCode by mutableStateOf("")
}

@Composable
private fun AuthModeToggle(
    mode: AuthMode,
    onModeChange: (AuthMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
        FilterChip(
            selected = mode == AuthMode.LOGIN,
            onClick = { onModeChange(AuthMode.LOGIN) },
            label = { Text(text = stringResource(R.string.auth_mode_login)) },
        )
        FilterChip(
            selected = mode == AuthMode.REGISTER,
            onClick = { onModeChange(AuthMode.REGISTER) },
            label = { Text(text = stringResource(R.string.auth_mode_register)) },
        )
    }
}

/** Four fields per decision C-M, username and invite code only in REGISTER mode — login needs neither. */
@Composable
private fun AuthFieldInputs(
    mode: AuthMode,
    fields: AuthFormFields,
) {
    if (mode == AuthMode.REGISTER) {
        OutlinedTextField(
            value = fields.username,
            onValueChange = { fields.username = it },
            label = { Text(text = stringResource(R.string.auth_field_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = fields.email,
        onValueChange = { fields.email = it },
        label = { Text(text = stringResource(R.string.auth_field_email)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = fields.password,
        onValueChange = { fields.password = it },
        label = { Text(text = stringResource(R.string.auth_field_password)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (mode == AuthMode.REGISTER) {
        OutlinedTextField(
            value = fields.inviteCode,
            onValueChange = { fields.inviteCode = it },
            label = { Text(text = stringResource(R.string.auth_field_invite_code)) },
            supportingText = { Text(text = stringResource(R.string.auth_invite_code_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AuthSubmitButton(
    state: AuthUiState.Form,
    fields: AuthFormFields,
    onValidationError: (Int?) -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (username: String, email: String, password: String, inviteCode: String) -> Unit,
) {
    Button(
        enabled = !state.submitting,
        onClick = {
            val error = validate(mode = state.mode, fields = fields)
            onValidationError(error)
            if (error != null) return@Button
            if (state.mode == AuthMode.LOGIN) {
                onLogin(fields.email, fields.password)
            } else {
                onRegister(fields.username, fields.email, fields.password, fields.inviteCode)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text =
                stringResource(
                    if (state.mode == AuthMode.LOGIN) R.string.auth_submit_login else R.string.auth_submit_register,
                ),
        )
    }
}

/**
 * Avoids a pointless round trip the server would refuse anyway (decision, task brief). Never a
 * replacement for handling [AuthError] below — the server stays the authority on whether an
 * invite code or an email is actually valid.
 */
private fun validate(
    mode: AuthMode,
    fields: AuthFormFields,
): Int? =
    when {
        mode == AuthMode.REGISTER && fields.username.isBlank() -> R.string.auth_validation_username_blank
        '@' !in fields.email -> R.string.auth_validation_email_invalid
        fields.password.length < MIN_PASSWORD_LENGTH -> R.string.auth_validation_password_short
        mode == AuthMode.REGISTER && fields.inviteCode.isBlank() -> R.string.auth_validation_invite_code_blank
        else -> null
    }

/** The one place an [AuthError] becomes copy — see decision C-L in the task brief. */
private fun AuthError.messageRes(): Int =
    when (this) {
        AuthError.InvalidCredentials -> R.string.auth_error_invalid_credentials
        AuthError.InviteCodeRejected -> R.string.auth_error_invite_code_rejected
        AuthError.EmailOrUsernameTaken -> R.string.auth_error_email_or_username_taken
        AuthError.RegistrationRefused -> R.string.auth_error_registration_refused
        AuthError.AccountCreatedNotSignedIn -> R.string.auth_error_account_created_not_signed_in
        AuthError.Offline -> R.string.auth_error_offline
        AuthError.Unknown -> R.string.auth_error_unknown
    }
