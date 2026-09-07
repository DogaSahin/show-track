package com.anarky.showtrack.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// A neutral-first palette with one accent. Surfaces carry no hue of their own so cover art — the
// only saturated thing on most screens — is never competing with the chrome around it; the accent
// appears on the primary action and nowhere else. Replaces the Android Studio template's
// Purple80/Purple40 set, which was never chosen for this app.

/** Coral. The single accent: primary buttons, selection, interactive emphasis. */
val Coral60 = Color(0xFFFF6B5B)
val Coral40 = Color(0xFFD64A38)
val Coral20 = Color(0xFF5C1A12)
val Coral95 = Color(0xFFFFEDEA)

// Neutrals, dark. Not pure black: an OLED-black surface makes every elevation change invisible and
// turns dividers into the only depth cue there is.
val Ink05 = Color(0xFF0D0D10)
val Ink10 = Color(0xFF141418)
val Ink20 = Color(0xFF1E1E24)
val Ink30 = Color(0xFF2A2A32)
val Ink60 = Color(0xFF8E8E9A)
val Ink90 = Color(0xFFE6E6EA)

// Neutrals, light.
val Paper99 = Color(0xFFFCFCFD)
val Paper95 = Color(0xFFF2F2F5)
val Paper90 = Color(0xFFE6E6EA)
val Paper70 = Color(0xFFB4B4BE)
val Paper40 = Color(0xFF5A5A66)
val Paper10 = Color(0xFF17171B)

/** Error. Deliberately shifted toward red-violet so it never reads as the coral accent. */
val Crimson60 = Color(0xFFFF8A80)
val Crimson40 = Color(0xFFB3261E)
val Crimson20 = Color(0xFF5C1015)
val Crimson95 = Color(0xFFFCEEEE)
