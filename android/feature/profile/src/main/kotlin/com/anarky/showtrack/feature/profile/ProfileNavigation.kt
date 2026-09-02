package com.anarky.showtrack.feature.profile

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
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
 */
fun NavGraphBuilder.profileEntry(onNavigate: (AppRoute) -> Unit) {
    composable<ProfileRoute> {
        ProfileScreen(onSignedOut = signOutNavigation(onNavigate))
    }
}

/**
 * The mapping sign-out drives, pulled out of the `composable<ProfileRoute> { }` lambda above so it
 * is reachable by a plain unit test. [profileEntry]'s lambda constructs `ProfileScreen` WITHOUT
 * passing `viewModel`, which evaluates its `hiltViewModel()` default — composing THAT lambda as
 * written would need a Hilt harness this module does not have — so a test cannot compose
 * [profileEntry] itself to observe what a confirmed sign-out does; it can call this function
 * directly instead.
 *
 * What IS covered: `ProfileViewModelTest` pins `signOut()` → `AuthRepository.logout()`;
 * `ProfileNavigationTest` pins this function, `onSignedOut` (the parameter) →
 * `onNavigate(AuthRoute)`; `ProfileResumeTest` (round 2) composes `ProfileScreen`'s stateful
 * overload with a fake `ProfileViewModel` passed explicitly — never evaluating the
 * `hiltViewModel()` default at all — to pin the stats fetch's own wiring, with no Hilt harness
 * needed for that.
 *
 * What is NOT, and this module has three such gaps where `:feature:library` has one — corrected
 * (review finding, round 2): only gap 3 below needs Hilt. Gaps 1 and 2 are reachable the exact
 * same Hilt-free way `ProfileResumeTest` reaches the stats wiring; they are open because nobody
 * has written that test yet, not because anything blocks it:
 *   1. The confirm button's `onClick` inside `ProfileScreen`'s `AlertDialog` → `viewModel.signOut()`.
 *   2. `ProfileScreen`'s `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` →
 *      `onSignedOut()`. Delete that `LaunchedEffect` entirely and sign-out silently stops
 *      navigating anywhere — `signedOut` still flips, `ProfileViewModelTest` stays green, nothing
 *      fails.
 *   3. The BINDING one line above — `onSignedOut = signOutNavigation(onNavigate)` — same failure
 *      mode as [libraryEntry]: change it to `onSignedOut = {}` and every existing test, including
 *      `ProfileNavigationTest`, stays green while sign-out goes unreachable again. THIS one lives
 *      inside [profileEntry]'s own lambda, which evaluates the `hiltViewModel()` default, so
 *      closing it does need a Hilt-composed harness — `:feature:library`'s `LibraryEntryHiltTest`
 *      is the pattern, built against the identical gap in `LibraryNavigation.kt`'s
 *      `searchNavigation` binding — but this module has not adopted it: `:feature:profile`
 *      declares no `hilt-android-testing`/`HiltTestActivity` of its own. Do not read library's fix
 *      as covering this file too.
 */
internal fun signOutNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(AuthRoute) }
