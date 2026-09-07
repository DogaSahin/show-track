package com.anarky.showtrack.feature.discover

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The `@AndroidEntryPoint` host [DiscoverEntryHiltTest] composes into. Copied from
 * `:feature:library`'s `HiltTestActivity` (task 9c.0's own brief: duplicating this 23-line file
 * per module is deliberate). See that file's own KDoc for the full reasoning.
 *
 * Test-scoped on purpose: it lives in `src/test`, registered only in `src/test/AndroidManifest.xml`.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
