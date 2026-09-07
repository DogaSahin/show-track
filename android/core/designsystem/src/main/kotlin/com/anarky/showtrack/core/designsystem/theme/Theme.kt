package com.anarky.showtrack.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Every role is set explicitly, including the five surfaceContainer* steps. This is not
// completeness for its own sake: any role left out is DERIVED by Material from the ones given, and
// the derivation tints toward `primary`. A first pass that set twenty roles produced lavender-grey
// cards on a white background (Card defaults to surfaceContainerLow) and a pink COMPLETED badge
// (StatusPresentation asks for tertiaryContainer) — neither colour appeared anywhere in the
// palette, and neither was chosen by anyone.

private val DarkColorScheme =
    darkColorScheme(
        primary = Iris60,
        onPrimary = Iris20,
        primaryContainer = Iris40,
        onPrimaryContainer = Iris95,
        inversePrimary = Iris40,
        secondary = Steel60,
        onSecondary = Steel20,
        secondaryContainer = Steel20,
        onSecondaryContainer = Steel90,
        tertiary = Teal60,
        onTertiary = Teal20,
        tertiaryContainer = Teal20,
        onTertiaryContainer = Teal90,
        background = Ink05,
        onBackground = Ink90,
        surface = Ink05,
        onSurface = Ink90,
        surfaceVariant = Ink20,
        onSurfaceVariant = Ink60,
        surfaceTint = Iris60,
        inverseSurface = Ink90,
        inverseOnSurface = Ink10,
        surfaceDim = Ink03,
        surfaceBright = Ink30,
        surfaceContainerLowest = Ink03,
        surfaceContainerLow = Ink10,
        surfaceContainer = Ink15,
        surfaceContainerHigh = Ink20,
        surfaceContainerHighest = Ink30,
        error = Crimson60,
        onError = Crimson20,
        errorContainer = Crimson20,
        onErrorContainer = Crimson60,
        outline = Ink40,
        outlineVariant = Ink30,
        scrim = Color.Black,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Iris40,
        onPrimary = Paper100,
        primaryContainer = Iris90,
        onPrimaryContainer = Iris20,
        inversePrimary = Iris60,
        secondary = Steel40,
        onSecondary = Paper100,
        secondaryContainer = Steel90,
        onSecondaryContainer = Steel20,
        tertiary = Teal40,
        onTertiary = Paper100,
        tertiaryContainer = Teal90,
        onTertiaryContainer = Teal20,
        background = Paper99,
        onBackground = Paper10,
        surface = Paper99,
        onSurface = Paper10,
        surfaceVariant = Paper95,
        onSurfaceVariant = Paper40,
        surfaceTint = Iris40,
        inverseSurface = Paper20,
        inverseOnSurface = Paper95,
        surfaceDim = Paper90,
        surfaceBright = Paper100,
        surfaceContainerLowest = Paper100,
        surfaceContainerLow = Paper99,
        surfaceContainer = Paper97,
        surfaceContainerHigh = Paper95,
        surfaceContainerHighest = Paper92,
        error = Crimson40,
        onError = Paper100,
        errorContainer = Crimson90,
        onErrorContainer = Crimson20,
        outline = Paper70,
        outlineVariant = Paper90,
        scrim = Color.Black,
    )

/**
 * [dynamicColor] defaults to **false**, and that is the point of this theme rather than an
 * oversight. With it on, Android 12+ derives every colour from the user's *wallpaper*, so the app
 * has no appearance of its own to recognise — two phones running ShowTrack look like two different
 * apps. The parameter stays so a caller (or a future setting) can opt back in; the default is the
 * decision.
 */
@Composable
fun ShowTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
