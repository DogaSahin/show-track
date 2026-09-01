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
 * Public, unlike `UserMediaStatus.label()` in `StatusPresentation.kt`: that mapping is only ever
 * consumed from inside this module (`StatusTab`, `MediaCard`), while this one is consumed from
 * `:feature:search` today and, per decision C-T, will be from a second screen later — a feature
 * module re-implementing this mapping itself would be exactly the shared-presentation duplication
 * C-T exists to prevent.
 */
@Composable
fun MediaSource.displayName(): String =
    stringResource(
        when (this) {
            MediaSource.ANILIST -> R.string.media_source_anilist
            MediaSource.TMDB -> R.string.media_source_tmdb
        },
    )
