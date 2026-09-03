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
 * where `:feature:library` had one, corrected review finding round 2, closed task 9c.0):
 *   1. The confirm button's `onClick` inside `ProfileScreen`'s `AlertDialog` → `viewModel.signOut()`.
 *      Reachable the Hilt-free way `ProfileResumeTest` reaches the stats wiring; still open —
 *      nobody has written that test yet, not because anything blocks it.
 *   2. `ProfileScreen`'s `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` →
 *      `onSignedOut()`. Same status as gap 1: reachable without Hilt, still open.
 *   3. The BINDING one line above — `onSignedOut = signOutNavigation(onNavigate)` — same failure
 *      mode as [libraryEntry]: change it to `onSignedOut = {}` and every OTHER existing test,
 *      including `ProfileNavigationTest`, stayed green while sign-out went unreachable. THIS one
 *      lives inside [profileEntry]'s own lambda, which evaluates the `hiltViewModel()` default, so
 *      closing it needed a Hilt-composed harness — `:feature:library`'s `LibraryEntryHiltTest` was
 *      the pattern, built against the identical gap in `LibraryNavigation.kt`'s `searchNavigation`
 *      binding. **CLOSED (task 9c.0):** `:feature:profile` now declares its own
 *      `hilt-android-testing`/`HiltTestActivity`/`TestDataModule`, and `ProfileEntryHiltTest`'s
 *      `` `confirming sign-out navigates to AuthRoute` `` composes this exact binding and fails if
 *      it is set to `{}`.
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
