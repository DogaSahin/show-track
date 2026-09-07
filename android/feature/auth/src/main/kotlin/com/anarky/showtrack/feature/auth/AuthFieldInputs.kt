package com.anarky.showtrack.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Four fields per decision C-M, username and invite code only in REGISTER mode — login needs
 * neither. [AnimatedVisibility] rather than a bare `if`: switching mode otherwise makes two fields
 * pop in and the button jump, which reads as a glitch rather than as a change of form.
 */
@Composable
internal fun AuthFieldInputs(
    mode: AuthMode,
    fields: AuthFormFields,
    onSubmit: () -> Unit,
) {
    val isRegister = mode == AuthMode.REGISTER
    Column(verticalArrangement = Arrangement.spacedBy(space = 12.dp)) {
        AuthFieldReveal(visible = isRegister) {
            AuthTextField(
                value = fields.username,
                onValueChange = { fields.username = it },
                spec =
                    AuthFieldSpec(
                        labelRes = R.string.auth_field_username,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
            )
        }
        AuthTextField(
            value = fields.email,
            onValueChange = { fields.email = it },
            spec =
                AuthFieldSpec(
                    labelRes = R.string.auth_field_email,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
        )
        AuthTextField(
            value = fields.password,
            onValueChange = { fields.password = it },
            spec =
                AuthFieldSpec(
                    labelRes = R.string.auth_field_password,
                    keyboardType = KeyboardType.Password,
                    // In REGISTER the invite code still follows, so Done here would submit a form
                    // with an empty required field; in LOGIN the password IS the last field and
                    // Done means "log me in".
                    imeAction = if (isRegister) ImeAction.Next else ImeAction.Done,
                    isPassword = true,
                ),
            onSubmit = onSubmit,
        )
        AuthFieldReveal(visible = isRegister) {
            AuthTextField(
                value = fields.inviteCode,
                onValueChange = { fields.inviteCode = it },
                spec =
                    AuthFieldSpec(
                        labelRes = R.string.auth_field_invite_code,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        supportingTextRes = R.string.auth_invite_code_help,
                    ),
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun AuthFieldReveal(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/**
 * One styling decision in one place, so four fields cannot drift apart. `autoCorrectEnabled` is off
 * and capitalisation is [KeyboardCapitalization.None] throughout: every field here is an
 * identifier, and an autocapitalised email is a login failure whose cause the user cannot see.
 *
 * [onSubmit] is wired to `onDone` only. A field carrying `ImeAction.Next` never reaches it —
 * `KeyboardActions`' unset handlers fall through to Compose's defaults, which is what moves focus
 * to the next field.
 */
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    spec: AuthFieldSpec,
    onSubmit: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = stringResource(spec.labelRes)) },
        supportingText = spec.supportingTextRes?.let { { Text(text = stringResource(it)) } },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        visualTransformation =
            if (spec.isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        // A text button, not an eye icon: `Icons.Filled.Visibility` lives in
        // `material-icons-extended`, an artifact this project has never depended on, and
        // "Show"/"Hide" needs no legend or contentDescription of its own.
        trailingIcon =
            if (!spec.isPassword) {
                null
            } else {
                {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(
                            text =
                                stringResource(
                                    if (revealed) R.string.auth_password_hide else R.string.auth_password_show,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = spec.keyboardType,
                imeAction = spec.imeAction,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
        keyboardActions = KeyboardActions(onDone = onSubmit?.let { { it() } }),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}
