package com.anarky.showtrack.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// A neutral-first palette with one accent and two supporting hues. Surfaces carry only a trace of
// hue so cover art — the only saturated thing on most screens — is never competing with the chrome
// around it. Replaces the Android Studio template's Purple80/Purple40 set, which was never chosen
// for this app.
//
// The three hues are not decoration: `UserMediaStatus` needs five distinguishable badge colours and
// resolves them through Material's primary/secondary/tertiary/error/surfaceVariant roles
// (StatusPresentation.containerColor). They are picked to sit on one arc — violet → steel →
// teal — so five badges in one list read as a set rather than as five unrelated stickers, with red
// reserved for the one status that means something went wrong.

/** Iris. The accent: primary actions, selection, and WATCHING. */
val Iris60 = Color(0xFF8B84FF)
val Iris40 = Color(0xFF5546D9)
val Iris20 = Color(0xFF1B1547)
val Iris90 = Color(0xFFDDD9FF)
val Iris95 = Color(0xFFEDEBFF)

/** Steel. Secondary: PLANNED — a thing intended, not yet begun, so it stays the quietest hue. */
val Steel60 = Color(0xFF8AA0D8)
val Steel40 = Color(0xFF41598F)
val Steel20 = Color(0xFF141E33)
val Steel90 = Color(0xFFD6E0F7)
val Steel95 = Color(0xFFE9EFFC)

/** Teal. Tertiary: COMPLETED — the one status that means "finished", and green reads that way. */
val Teal60 = Color(0xFF56CFBC)
val Teal40 = Color(0xFF11796C)
val Teal20 = Color(0xFF07281F)
val Teal90 = Color(0xFFBDEFE6)
val Teal95 = Color(0xFFDCF7F2)

// Neutrals, dark. Cool rather than achromatic — a few points of blue in the greys keeps the accent
// from reading as a sticker on a slate background. Not pure black either: an OLED-black surface
// makes every elevation change invisible and leaves dividers as the only depth cue there is.
val Ink03 = Color(0xFF08080D)
val Ink05 = Color(0xFF0B0B11)
val Ink10 = Color(0xFF121219)
val Ink15 = Color(0xFF17171F)
val Ink20 = Color(0xFF1C1C26)
val Ink30 = Color(0xFF282834)
val Ink40 = Color(0xFF34343F)
val Ink60 = Color(0xFF8B8B9E)
val Ink90 = Color(0xFFE7E7EE)

// Neutrals, light. The same cool cast, mirrored.
val Paper100 = Color(0xFFFFFFFF)
val Paper99 = Color(0xFFFCFCFE)
val Paper97 = Color(0xFFF7F7FA)
val Paper95 = Color(0xFFF1F1F6)
val Paper92 = Color(0xFFEAEAF1)
val Paper90 = Color(0xFFE5E5EC)
val Paper70 = Color(0xFFB2B2C0)
val Paper40 = Color(0xFF56566A)
val Paper20 = Color(0xFF2A2A36)
val Paper10 = Color(0xFF15151D)

/** Error. Warm and unambiguously red, so it can never be mistaken for the cool accent. */
val Crimson60 = Color(0xFFFF8A80)
val Crimson40 = Color(0xFFB3261E)
val Crimson20 = Color(0xFF4A1116)
val Crimson90 = Color(0xFFFAD8D5)
val Crimson95 = Color(0xFFFCEEEE)
