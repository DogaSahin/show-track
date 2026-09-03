package com.anarky.showtrack.feature.feed

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The `@AndroidEntryPoint` host [FeedEntryHiltTest] composes into. Copied from `:feature:groups`'
 * `HiltTestActivity` (task 9c.0's brief: duplicating this file per module is deliberate). See that
 * file's own KDoc for the full reasoning.
 *
 * Test-scoped on purpose: it lives in `src/test`, registered only in `src/test/AndroidManifest.xml`.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
