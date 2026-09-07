package com.anarky.showtrack.feature.library

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The `@AndroidEntryPoint` host [LibraryEntryHiltTest] composes into.
 *
 * `createComposeRule()` — the pattern `LibraryScreenTest` uses — is documented as exactly
 * `createAndroidComposeRule<ComponentActivity>()`: a real (Robolectric-shadowed) `ComponentActivity`
 * gets launched either way, it is just the default one when no type is named. That default
 * `ComponentActivity` is not a Hilt entry point, and `hiltViewModel()` needs its host to be one —
 * `libraryEntry()`'s stateful `LibraryScreen` resolves `LibraryViewModel` through `hiltViewModel()`,
 * which asks the hosting Activity for a `HiltViewModelFactory` and throws ("... is not a
 * GeneratedComponentManagerHolder") the moment the host isn't `@AndroidEntryPoint`. This class,
 * plus the `<activity>` entry in `src/test/AndroidManifest.xml` that registers it, is what
 * `createAndroidComposeRule<HiltTestActivity>()` launches instead.
 *
 * Test-scoped on purpose: it lives in `src/test`, registered only in `src/test/AndroidManifest.xml`,
 * so it never reaches `:app`'s manifest or a shipped build.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
