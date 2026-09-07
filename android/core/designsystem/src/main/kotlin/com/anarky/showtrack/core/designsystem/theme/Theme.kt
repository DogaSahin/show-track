package com.anarky.showtrack.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = Coral60,
        onPrimary = Coral20,
        primaryContainer = Coral40,
        onPrimaryContainer = Coral95,
        secondary = Ink60,
        onSecondary = Ink05,
        background = Ink05,
        onBackground = Ink90,
        surface = Ink10,
        onSurface = Ink90,
        surfaceVariant = Ink20,
        onSurfaceVariant = Ink60,
        surfaceContainer = Ink20,
        surfaceContainerHigh = Ink30,
        outline = Ink30,
        outlineVariant = Ink20,
        error = Crimson60,
        onError = Crimson20,
        errorContainer = Crimson20,
        onErrorContainer = Crimson60,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Coral40,
        onPrimary = Paper99,
        primaryContainer = Coral95,
        onPrimaryContainer = Coral20,
        secondary = Paper40,
        onSecondary = Paper99,
        background = Paper99,
        onBackground = Paper10,
        surface = Paper99,
        onSurface = Paper10,
        surfaceVariant = Paper95,
        onSurfaceVariant = Paper40,
        surfaceContainer = Paper95,
        surfaceContainerHigh = Paper90,
        outline = Paper70,
        outlineVariant = Paper90,
        error = Crimson40,
        onError = Paper99,
        errorContainer = Crimson95,
        onErrorContainer = Crimson40,
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
