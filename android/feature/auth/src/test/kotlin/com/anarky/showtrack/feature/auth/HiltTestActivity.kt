package com.anarky.showtrack.feature.auth

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The `@AndroidEntryPoint` host [AuthEntryHiltTest] composes into. Copied from
 * `:feature:library`'s `HiltTestActivity` (task 9c.0's own brief: duplicating this 23-line file
 * per module is deliberate — a shared `:core:testing` module would have to be added to
 * `ModuleRules`' allowed-dependency table and to the TestKit tests that prove those rules fail a
 * build, which is a larger change than the duplication avoids). See that file's own KDoc for the
 * full reasoning: `createComposeRule()` launches a plain, non-Hilt `ComponentActivity`, and
 * `hiltViewModel()` throws against one.
 *
 * Test-scoped on purpose: it lives in `src/test`, registered only in `src/test/AndroidManifest.xml`,
 * so it never reaches `:app`'s manifest or a shipped build.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
