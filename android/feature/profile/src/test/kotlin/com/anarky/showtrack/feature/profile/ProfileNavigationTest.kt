package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard `ProfileViewModelTest` alone cannot provide. That test pins `signOut()` →
 * `AuthRepository.logout()`; this pins [signOutNavigation] itself → `onNavigate(AuthRoute)`. A
 * plain JUnit test on the extracted function needs neither Compose nor Hilt, unlike composing
 * `ProfileScreen`, which resolves `ProfileViewModel` through `hiltViewModel()` — UNLESS a fake
 * `ProfileViewModel` is passed explicitly, which never evaluates that default at all.
 *
 * `ProfileScreenTest` (task 9b.5, round 1) closed one gap — it drives the `internal` stateless
 * `ProfileScreen` overload directly, with no ViewModel at all, which is enough to pin what STRING
 * the stats block renders. `ProfileResumeTest` (round 2) closed another — it composes the PUBLIC
 * stateful overload with a fake `ProfileViewModel` passed explicitly (`FavoritesResumeTest`'s own
 * pattern), which needs no Hilt either, and pins that the stats fetch is actually wired to a live
 * `Lifecycle` and to the retry button.
 *
 * Corrected (review finding, round 2 — an earlier version of this KDoc claimed all three gaps
 * below "live in the `hiltViewModel()`-wired public overload... and this module still has no Hilt
 * test harness", which `ProfileResumeTest` disproves for gaps 1 and 2): those two are reachable
 * the exact same way `ProfileResumeTest` reaches the stats wiring — a fake `ProfileViewModel`
 * passed explicitly to the stateful overload, no Hilt required. They are open because nobody has
 * written that test yet, not because anything blocks it. Gap 3 is different in kind: it lives in
 * `profileEntry`'s `composable<ProfileRoute> { }` registration itself, which constructs
 * `ProfileScreen` WITHOUT passing `viewModel` — so closing it means composing that lambda as
 * written, which DOES evaluate the `hiltViewModel()` default and plausibly does need a Hilt-composed
 * harness (`:feature:library`'s `LibraryEntryHiltTest` is the pattern; see [signOutNavigation]'s
 * own KDoc for why this module has not adopted it).
 *
 * What no test pins yet, and there are three such gaps here where `:feature:library` has one:
 *   1. `ProfileScreen`'s `AlertDialog` confirm button's `onClick` → `viewModel.signOut()`.
 *   2. `ProfileScreen`'s `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` →
 *      `onSignedOut()`. Delete that effect and sign-out silently stops navigating anywhere while
 *      this suite, including `ProfileViewModelTest`, stays green.
 *   3. The BINDING at `ProfileNavigation.kt`'s `onSignedOut = signOutNavigation(onNavigate)` —
 *      change it to `onSignedOut = {}` and this test still passes while sign-out goes unreachable
 *      again.
 */
class ProfileNavigationTest {
    @Test
    fun `sign-out navigates to AuthRoute`() {
        val navigated = mutableListOf<AppRoute>()

        signOutNavigation(onNavigate = navigated::add).invoke()

        assertEquals(listOf(AuthRoute), navigated)
    }
}
