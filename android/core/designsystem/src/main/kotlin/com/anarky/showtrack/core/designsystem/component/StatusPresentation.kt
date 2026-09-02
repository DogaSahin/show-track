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

/**
 * Public since task 9b.5 — decision C-T: `:feature:profile`'s library-stats block labels the same
 * five [UserMediaStatus] values for its status breakdown, and a second, feature-owned copy of this
 * mapping is exactly the shared-presentation duplication C-T exists to prevent (the identical
 * reasoning `MediaSourcePresentation.displayName`'s KDoc gives for being public rather than
 * internal). [containerColor] stays `internal`: nothing outside this module needs the colour, only
 * the text.
 */
@Composable
fun UserMediaStatus.label(): String =
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
