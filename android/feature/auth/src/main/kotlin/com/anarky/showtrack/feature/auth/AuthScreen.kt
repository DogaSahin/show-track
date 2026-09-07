package com.anarky.showtrack.feature.auth

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import com.anarky.showtrack.core.designsystem.theme.WordmarkStyle

/** Caps the form on tablets and in landscape: a text field stretched to 900dp is unreadable. */
private val FORM_MAX_WIDTH = 420.dp

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI — the same
 * shape `LibraryScreen` uses.
 *
 * `onAuthenticated` fires exactly once per successful login/register, keyed on [AuthUiState] so
 * the effect does not re-fire on an unrelated recomposition (e.g. a config change) while already
 * `Authenticated`. It now takes [AuthUiState.Authenticated.isNewAccount] (task 9b.6): `AuthNavigation`
 * uses it to route a fresh registration to the AniList import screen instead of straight to the
 * library, which `LaunchedEffect` reads off `state` here rather than this composable choosing a
 * route itself — that choice belongs to `:app` (architecture rule 1). `ShowTrackNavHost` resolves
 * either destination to a navigation that clears `AuthRoute` off the stack, so Back cannot return
 * here from either.
 */
@Composable
fun AuthScreen(
    onAuthenticated: (isNewAccount: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        (state as? AuthUiState.Authenticated)?.let { onAuthenticated(it.isNewAccount) }
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
 *
 * **On the layout.** [BoxWithConstraints] plus `heightIn(min = maxHeight)` *inside* the scroll is
 * what makes `Arrangement.Center` and `verticalScroll` coexist. A scroll modifier measures its
 * content with an infinite height constraint, so a plain `fillMaxSize()` Column inside one wraps
 * its children and `Center` has no free space left to distribute — the form silently pins to the
 * top. Giving the content a *minimum* height of the viewport restores the centring while still
 * letting it scroll once the keyboard, a long error, or a short landscape screen pushes it past
 * that height.
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

    // Hoisted out of the button so the last field's IME "Done" runs exactly the same path — a
    // keyboard submit that skipped validation would post a form the button itself would refuse.
    val submit = {
        val error = validate(mode = state.mode, fields = fields)
        validationError = error
        if (error == null) {
            if (state.mode == AuthMode.LOGIN) {
                onLogin(fields.email, fields.password)
            } else {
                onRegister(fields.username, fields.email, fields.password, fields.inviteCode)
            }
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = FORM_MAX_WIDTH)) {
                AuthHeader()
                Spacer(modifier = Modifier.size(40.dp))
                AuthFieldInputs(mode = state.mode, fields = fields, onSubmit = { submit() })
                Spacer(modifier = Modifier.size(24.dp))
                AuthMessages(state = state, validationError = validationError, onModeChange = onModeChange)
                AuthSubmitButton(state = state, onSubmit = { submit() })
                Spacer(modifier = Modifier.size(20.dp))
                AuthModeSwitch(
                    mode = state.mode,
                    enabled = !state.submitting,
                    onModeChange = {
                        // A stale "password too short" carried across the switch would be advice
                        // about a submission the other form never made.
                        validationError = null
                        onModeChange(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun AuthHeader() {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            // Uppercased here rather than in strings.xml: an all-caps string resource is read out
            // letter by letter by TalkBack, where this leaves the accessible text intact.
            text = stringResource(R.string.auth_wordmark).uppercase(),
            style = WordmarkStyle,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Validation and [AuthError] share one slot, because they are the same thing to the reader: the
 * reason the form did not go through. Deliberately no longer `ErrorState` — that is the
 * full-screen empty-state component (centred icon, centred body, its own retry button), and a
 * form's failure is a line of text under the fields, not a page.
 *
 * ErrorState's `onRetry` goes with it, and nothing is lost: the only "retry" it offered here was
 * `onModeChange(state.mode)`, i.e. rebuild a fresh `Form` with `error = null`, which the user
 * reaches anyway by correcting a field and submitting again. The one case that carried a real
 * action — the two errors whose answer is "go and sign in" — keeps it.
 *
 * Not wrapped in `AnimatedVisibility`, unlike the register-only fields: its content keeps composing
 * through the exit transition, so the message would have to be retained past the state that
 * produced it or blank itself mid-fade. An error appearing at once is the right behaviour anyway.
 */
@Composable
private fun AuthMessages(
    state: AuthUiState.Form,
    validationError: Int?,
    onModeChange: (AuthMode) -> Unit,
) {
    val messageRes = validationError ?: state.error?.messageRes() ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(text = stringResource(messageRes), style = MaterialTheme.typography.bodyMedium)
            if (state.error?.pointsAtSigningIn == true) {
                // defaultMinSize overrides ButtonDefaults' 58dp minimum width, which centres a
                // short label inside it and leaves the action visibly indented from the message
                // it belongs to. contentPadding alone does not touch that minimum.
                TextButton(
                    onClick = { onModeChange(AuthMode.LOGIN) },
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minWidth = Dp.Unspecified, minHeight = 32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.auth_error_action_switch_to_login),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.size(16.dp))
}

@Composable
private fun AuthSubmitButton(
    state: AuthUiState.Form,
    onSubmit: () -> Unit,
) {
    Button(
        enabled = !state.submitting,
        onClick = onSubmit,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        // The spinner replaces the label rather than sitting beside it: swapping keeps the button
        // one fixed size, where adding a spinner next to the label reflows it on every submit.
        if (state.submitting) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text =
                    stringResource(
                        if (state.mode == AuthMode.LOGIN) R.string.auth_submit_login else R.string.auth_submit_register,
                    ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Below the form, not above it, and prose rather than two chips. The two `FilterChip`s this
 * replaces read as a filter over one form; this reads as what it is — a way out of this form into
 * the other one. It also removes the duplicate "Log in" that `AuthEntryHiltTest` had to
 * disambiguate with `isButtonRole()`: in LOGIN mode that string now appears exactly once.
 */
@Composable
private fun AuthModeSwitch(
    mode: AuthMode,
    enabled: Boolean,
    onModeChange: (AuthMode) -> Unit,
) {
    val isLogin = mode == AuthMode.LOGIN
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                stringResource(
                    if (isLogin) R.string.auth_switch_to_register_prompt else R.string.auth_switch_to_login_prompt,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            enabled = enabled,
            onClick = { onModeChange(if (isLogin) AuthMode.REGISTER else AuthMode.LOGIN) },
        ) {
            Text(
                text =
                    stringResource(
                        if (isLogin) R.string.auth_switch_to_register_action else R.string.auth_switch_to_login_action,
                    ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Preview(name = "Login light", showBackground = true)
@Preview(name = "Login dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun AuthScreenLoginPreview() {
    AuthScreenPreview(state = AuthUiState.Form(mode = AuthMode.LOGIN))
}

@Preview(name = "Register dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun AuthScreenRegisterPreview() {
    AuthScreenPreview(state = AuthUiState.Form(mode = AuthMode.REGISTER))
}

@Preview(name = "Error dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun AuthScreenErrorPreview() {
    AuthScreenPreview(state = AuthUiState.Form(mode = AuthMode.LOGIN, error = AuthError.EmailOrUsernameTaken))
}

/**
 * The fixed [Box] is what makes these previews meaningful: `AuthScreen` centres itself against the
 * viewport, so rendered at wrap-content height there is nothing to centre within and the layout
 * being previewed never happens.
 */
@Composable
private fun AuthScreenPreview(state: AuthUiState.Form) {
    ShowTrackTheme {
        Box(modifier = Modifier.size(width = 360.dp, height = 720.dp)) {
            AuthScreen(
                state = state,
                onModeChange = {},
                onLogin = { _, _ -> },
                onRegister = { _, _, _, _ -> },
            )
        }
    }
}
