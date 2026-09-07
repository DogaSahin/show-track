package com.anarky.showtrack.feature.auth

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * A field's fixed description — everything about it that does not change as the user types.
 *
 * Bundled rather than passed as five arguments for a reason worth stating: `AuthTextField` needs
 * label, keyboard type, IME action, help text and "is this the password" to render a field, and
 * spreading those across its parameter list put it at ten parameters, which is a signature nobody
 * reads. Grouping them also puts each field's whole definition on screen at its call site.
 *
 * [isPassword] lives here rather than being inferred from [keyboardType] because they answer
 * different questions — the keyboard type asks what the IME should offer, this asks whether the
 * text is masked and gets a reveal toggle.
 */
internal data class AuthFieldSpec(
    val labelRes: Int,
    val keyboardType: KeyboardType,
    val imeAction: ImeAction,
    val supportingTextRes: Int? = null,
    val isPassword: Boolean = false,
)
