package com.anarky.showtrack.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaType

/** The user-facing name of a [MediaType]: never the raw enum constant, as with `displayName()`. */
@Composable
fun MediaType.label(): String =
    stringResource(
        when (this) {
            MediaType.ANIME -> R.string.media_type_anime
            MediaType.TV -> R.string.media_type_tv
        },
    )

/**
 * "Anime · 2021": the one-line kind-and-year caption cards across the app share. The year is
 * dropped, separator and all, when the title has none.
 */
@Composable
fun Media.typeAndYear(): String = listOfNotNull(type.label(), year?.toString()).joinToString(separator = " · ")
