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
 * internal). [markColor] stays `internal`: nothing outside this module needs the colour, only
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

/**
 * The status's own colour, for a mark small enough that it must carry the hue on its own.
 *
 * The `*Container` roles, not these, were right while this was a filled pill behind dark text — a
 * container is a *background*. A 7dp dot is foreground, and the containers are pale by
 * construction: in the light scheme they made a lavender speck and a mint speck that neither
 * carried their meaning nor survived being glanced at. These are the corresponding foreground
 * roles, which Material already guarantees to be legible against `surface` in both schemes.
 *
 * PAUSED stays deliberately neutral: it is the absence of an active state, and giving it a fifth
 * hue would say something the status does not mean.
 */
@Composable
internal fun UserMediaStatus.markColor(): Color =
    when (this) {
        UserMediaStatus.WATCHING -> MaterialTheme.colorScheme.primary
        UserMediaStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        UserMediaStatus.DROPPED -> MaterialTheme.colorScheme.error
        UserMediaStatus.PLANNED -> MaterialTheme.colorScheme.secondary
        UserMediaStatus.PAUSED -> MaterialTheme.colorScheme.outline
    }
