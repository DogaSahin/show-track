package com.anarky.showtrack.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.anarky.showtrack.core.model.UserMediaStatus

// Shared label + colour mapping for UserMediaStatus, consumed by both StatusTab (a selectable
// filter control) and the read-only status badge inside MediaCard. They are different controls
// (per the task brief, MediaCard must not reuse StatusTab), but they still describe the same five
// values, and a mapping duplicated across both would drift the moment a sixth status is added.

internal fun UserMediaStatus.label(): String =
    when (this) {
        UserMediaStatus.WATCHING -> "Watching"
        UserMediaStatus.COMPLETED -> "Completed"
        UserMediaStatus.DROPPED -> "Dropped"
        UserMediaStatus.PLANNED -> "Planned"
        UserMediaStatus.PAUSED -> "Paused"
    }

@Composable
internal fun UserMediaStatus.containerColor(): Color =
    when (this) {
        UserMediaStatus.WATCHING -> MaterialTheme.colorScheme.primaryContainer
        UserMediaStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
        UserMediaStatus.DROPPED -> MaterialTheme.colorScheme.errorContainer
        UserMediaStatus.PLANNED -> MaterialTheme.colorScheme.secondaryContainer
        UserMediaStatus.PAUSED -> MaterialTheme.colorScheme.surfaceVariant
    }
