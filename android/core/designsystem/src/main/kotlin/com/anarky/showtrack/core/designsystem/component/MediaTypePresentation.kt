package com.anarky.showtrack.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaType

/**
 * The user-facing name of a [MediaType]: never the raw enum constant, as with `displayName()`.
 * `null` for [MediaType.UNKNOWN] — there is no honest word for it, so callers leave it out.
 */
@Composable
fun MediaType.label(): String? =
    when (this) {
        MediaType.ANIME -> stringResource(R.string.media_type_anime)
        MediaType.TV -> stringResource(R.string.media_type_tv)
        MediaType.UNKNOWN -> null
    }

/**
 * "Anime · 2021": the one-line kind-and-year caption cards across the app share. The year is
 * dropped, separator and all, when the title has none; so is the type when it is unknown.
 */
@Composable
fun Media.typeAndYear(): String = listOfNotNull(type.label(), year?.toString()).joinToString(separator = " · ")
