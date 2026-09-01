package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.UserMediaStatus

// Shared label + colour mapping for UserMediaStatus, consumed by both StatusTab (a selectable
// filter control) and the read-only status badge inside MediaCard. They are different controls
// (per the task brief, MediaCard must not reuse StatusTab), but they still describe the same five
// values, and a mapping duplicated across both would drift the moment a sixth status is added.

@Composable
internal fun UserMediaStatus.label(): String =
    stringResource(
        when (this) {
            UserMediaStatus.WATCHING -> R.string.status_watching
            UserMediaStatus.COMPLETED -> R.string.status_completed
            UserMediaStatus.DROPPED -> R.string.status_dropped
            UserMediaStatus.PLANNED -> R.string.status_planned
            UserMediaStatus.PAUSED -> R.string.status_paused
        },
    )

@Composable
internal fun UserMediaStatus.containerColor(): Color =
    when (this) {
        UserMediaStatus.WATCHING -> MaterialTheme.colorScheme.primaryContainer
        UserMediaStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
        UserMediaStatus.DROPPED -> MaterialTheme.colorScheme.errorContainer
        UserMediaStatus.PLANNED -> MaterialTheme.colorScheme.secondaryContainer
        UserMediaStatus.PAUSED -> MaterialTheme.colorScheme.surfaceVariant
    }
