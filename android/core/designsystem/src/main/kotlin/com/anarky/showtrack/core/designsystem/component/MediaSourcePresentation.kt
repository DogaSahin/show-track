package com.anarky.showtrack.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.MediaSource

/**
 * The brand name a user should see for a [MediaSource] — decision C-E: no user-facing surface
 * shows a raw enum constant. `MediaSource.name` (`"ANILIST"`, `"TMDB"`) is an internal identifier,
 * not copy, and it is also unlocalizable — a hardcoded `it.name` was exactly the bug this function
 * replaces (`SearchScreen`'s degraded-provider banner, "ANILIST isn't responding right now").
 *
 * Public for the same reason `UserMediaStatus.label()` in `StatusPresentation.kt` now is too (task
 * 9b.5 made that one public as well): both are consumed from outside this module —
 * `:feature:search` for this one, `:feature:profile` for that one — and a feature module
 * re-implementing either mapping itself would be exactly the shared-presentation duplication
 * decision C-T exists to prevent.
 *
 * `null` for [MediaSource.UNKNOWN]: there is no brand to name, so callers leave it out.
 */
@Composable
fun MediaSource.displayName(): String? =
    when (this) {
        MediaSource.ANILIST -> stringResource(R.string.media_source_anilist)
        MediaSource.TMDB -> stringResource(R.string.media_source_tmdb)
        MediaSource.UNKNOWN -> null
    }
