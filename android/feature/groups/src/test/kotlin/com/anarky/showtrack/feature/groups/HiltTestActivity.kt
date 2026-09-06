package com.anarky.showtrack.feature.groups

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The `@AndroidEntryPoint` host [GroupsEntryHiltTest] composes into. Copied from
 * `:feature:favorites`'s `HiltTestActivity` (task 9c.0's brief: duplicating this file per module
 * is deliberate). See that file's own KDoc for the full reasoning.
 *
 * Test-scoped on purpose: it lives in `src/test`, registered only in `src/test/AndroidManifest.xml`.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
