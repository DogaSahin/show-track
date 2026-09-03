package com.anarky.showtrack.feature.profile

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.ProfileRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * [signOutNavigation] (Gap 2, Phase 9a device walkthroughs): this screen used to have nowhere
 * to go and declared no `onNavigate` parameter at all — `FeedScreen`'s deliberate lack of a
 * default value is the same argument one layer down. Sign-out gave it its first destination.
 * `ShowTrackNavHost`'s router treats a navigation TO `AuthRoute` the same way it already treats
 * one to `LibraryRoute`: it reuses `navigateToAuthClearingStack()`, the same extension the
 * reactive `AuthGate` calls on a failed token refresh, so Back cannot return to a screen whose
 * session is already gone regardless of which path triggered the navigation.
 *
 * [onImportClick] (task 9b.6) is Profile's own door to [ImportRoute] — the other one is
 * `:feature:auth`'s post-register onboarding, wired in `AuthNavigation.kt`. Both go through this
 * shared route contract rather than one feature depending on the other (architecture rule 1).
 */
fun NavGraphBuilder.profileEntry(onNavigate: (AppRoute) -> Unit) {
    composable<ProfileRoute> {
        ProfileScreen(
            onSignedOut = signOutNavigation(onNavigate),
            onImportClick = importNavigation(onNavigate),
        )
    }
}

/**
 * The mapping sign-out drives, pulled out of the `composable<ProfileRoute> { }` lambda above so it
 * is reachable by a plain unit test without paying for a full Compose/Hilt composition just to pin
 * one `(AppRoute) -> Unit` mapping. [profileEntry]'s lambda constructs `ProfileScreen` WITHOUT
 * passing `viewModel`, which evaluates its `hiltViewModel()` default — so a test cannot compose
 * [profileEntry] itself to observe what a confirmed sign-out does using ONLY this function; it can
 * call this function directly for the mapping, and `ProfileEntryHiltTest` for the binding (below).
 *
 * What IS covered: `ProfileViewModelTest` pins `signOut()` → `AuthRepository.logout()`;
 * `ProfileNavigationTest` pins this function, `onSignedOut` (the parameter) →
 * `onNavigate(AuthRoute)`; `ProfileResumeTest` (round 2) composes `ProfileScreen`'s stateful
 * overload with a fake `ProfileViewModel` passed explicitly — never evaluating the
 * `hiltViewModel()` default at all — to pin the stats fetch's own wiring, with no Hilt harness
 * needed for that; `ProfileEntryHiltTest` (task 9c.0) composes the REAL [profileEntry] and asserts
 * both bindings below actually fire.
 *
 * What used to be open, and how each gap closed (history kept — this module had three such gaps
 * where `:feature:library` had one, corrected review finding round 2, closed task 9c.0; round 2's
 * own review then caught this comment itself overstating what was still open — see below):
 *   1. The confirm button's `onClick` inside `ProfileScreen`'s `AlertDialog` → `viewModel.signOut()`.
 *   2. `ProfileScreen`'s `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` →
 *      `onSignedOut()`.
 *   3. The BINDING one line above — `onSignedOut = signOutNavigation(onNavigate)` — same failure
 *      mode as [libraryEntry]: change it to `onSignedOut = {}` and every OTHER existing test,
 *      including `ProfileNavigationTest`, stayed green while sign-out went unreachable. THIS one
 *      lives inside [profileEntry]'s own lambda, which evaluates the `hiltViewModel()` default, so
 *      closing it needed a Hilt-composed harness — `:feature:library`'s `LibraryEntryHiltTest` was
 *      the pattern, built against the identical gap in `LibraryNavigation.kt`'s `searchNavigation`
 *      binding.
 *
 *   **All three are CLOSED (task 9c.0), by the SAME test.** `ProfileEntryHiltTest`'s
 *   `` `confirming sign-out navigates to AuthRoute` `` composes the real [profileEntry], taps the
 *   screen's "Sign out" button, then the confirm dialog's "Sign out" button, and asserts navigation
 *   reaches [AuthRoute] — that single assertion can only pass by going through the whole chain: the
 *   confirm click (gap 1) invoking `viewModel::signOut()` (wired at `ProfileScreen.kt:95`, itself
 *   pinned separately by `ProfileViewModelTest`), `signedOut` flipping to `true`, the
 *   `LaunchedEffect` reacting to it (gap 2, `ProfileScreen.kt:63`), and calling the real
 *   `onSignedOut` binding (gap 3). Mutation-verified independently for gaps 2+3 combined
 *   (`ProfileScreen.kt:63`'s `if (signedOut) onSignedOut()` → `if (false) onSignedOut()` fails the
 *   test) and for gap 1+2's wiring (`ProfileScreen.kt:95`'s `onSignOut = viewModel::signOut` → `{ }`
 *   also fails it) — reaching [AuthRoute] genuinely requires every link.
 *
 *   What is genuinely still open: no test drives gap 1 (the confirm click → `signOut()`) or gap 2
 *   (`signedOut` → the `LaunchedEffect` firing) *in isolation* the cheap, Hilt-free way
 *   `ProfileResumeTest` reaches the stats wiring — only the end-to-end Hilt test above exercises
 *   them, and only together. That is a coverage-shape note, not a functional gap: nothing about
 *   sign-out navigation is currently unreachable by any test.
 */
internal fun signOutNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(AuthRoute) }

/**
 * The mapping [ProfileScreen]'s import action drives (task 9b.6) — [signOutNavigation]'s own
 * reasoning applies identically: pulled out of [profileEntry]'s lambda so it is reachable by a
 * plain unit test, since [profileEntry] constructs `ProfileScreen` WITHOUT passing `viewModel`.
 * The BINDING itself (`onImportClick = importNavigation(onNavigate)`, one function above) is
 * covered by `ProfileEntryHiltTest`'s `` `tapping import navigates to ImportRoute` `` (task 9c.0) —
 * see [signOutNavigation]'s own KDoc, gap 3, for the identical history on the sign-out binding.
 */
internal fun importNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(ImportRoute) }
