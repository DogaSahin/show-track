package com.anarky.showtrack.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anarky.showtrack.core.designsystem.R

/**
 * The string resource for a canonical genre code (`sci_fi`), or `null` for a code this build does
 * not know. Kept apart from [genreLabel] so the mapping is unit-testable without Compose.
 */
@StringRes
internal fun genreLabelRes(code: String): Int? = GENRE_LABELS[code]

private val GENRE_LABELS: Map<String, Int> =
    mapOf(
        "action" to R.string.genre_action,
        "adventure" to R.string.genre_adventure,
        "comedy" to R.string.genre_comedy,
        "crime" to R.string.genre_crime,
        "documentary" to R.string.genre_documentary,
        "drama" to R.string.genre_drama,
        "ecchi" to R.string.genre_ecchi,
        "family" to R.string.genre_family,
        "fantasy" to R.string.genre_fantasy,
        "horror" to R.string.genre_horror,
        "kids" to R.string.genre_kids,
        "magical_girl" to R.string.genre_magical_girl,
        "mecha" to R.string.genre_mecha,
        "music" to R.string.genre_music,
        "mystery" to R.string.genre_mystery,
        "psychological" to R.string.genre_psychological,
        "reality" to R.string.genre_reality,
        "romance" to R.string.genre_romance,
        "sci_fi" to R.string.genre_sci_fi,
        "slice_of_life" to R.string.genre_slice_of_life,
        "soap" to R.string.genre_soap,
        "sports" to R.string.genre_sports,
        "supernatural" to R.string.genre_supernatural,
        "talk" to R.string.genre_talk,
        "thriller" to R.string.genre_thriller,
        "war" to R.string.genre_war,
        "western" to R.string.genre_western,
    )

/**
 * The user-facing name of a genre code: "Sci-Fi", never `sci_fi`. A code the server added after
 * this build shipped falls back to a title-cased version of itself ("space_opera" -> "Space Opera")
 * rather than disappearing. Callers keep comparing codes; only display goes through here.
 */
@Composable
fun genreLabel(code: String): String =
    genreLabelRes(code)?.let { stringResource(it) }
        ?: code.split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
