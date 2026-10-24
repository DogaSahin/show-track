package com.anarky.showtrack.feature.groups

import androidx.annotation.StringRes

// The server's bounds (backend/app/groups/schemas.py): a name or code past them is a 422 that
// would otherwise show as a generic failure. Blank is handled by the dialogs' disabled button.
// The server counts Unicode code points, not UTF-16 units, so an emoji is one character here too.
private const val MAX_GROUP_NAME_LENGTH = 64
private const val MAX_INVITE_CODE_LENGTH = 64

private fun String.characterCount(): Int = codePointCount(0, length)

@StringRes
internal fun groupNameError(name: String): Int? =
    if (name.characterCount() > MAX_GROUP_NAME_LENGTH) R.string.groups_validation_name_long else null

@StringRes
internal fun inviteCodeError(code: String): Int? =
    if (code.characterCount() > MAX_INVITE_CODE_LENGTH) R.string.groups_validation_code_long else null
