package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard `ProfileViewModelTest` alone cannot provide. That test pins `signOut()` →
 * `AuthRepository.logout()`; this pins [signOutNavigation] itself → `onNavigate(AuthRoute)`. A
 * plain JUnit test on the extracted function needs neither Compose nor Hilt, unlike composing
 * `ProfileScreen`, which resolves `ProfileViewModel` through `hiltViewModel()`.
 *
 * `ProfileScreenTest` (task 9b.5, round 1) closed the module's other gap — it drives the
 * `internal` stateless `ProfileScreen` overload directly, with no ViewModel and no Hilt in the
 * loop, which is enough to pin what STRING the stats block renders. It is NOT enough to close the
 * three gaps below: all three live in the `hiltViewModel()`-wired public overload or the graph
 * binding around it, neither of which the stateless overload touches, and this module still has
 * no Hilt test harness to compose either one.
 *
 * What neither test pins, and there are three such gaps here where `:feature:library` has one:
 *   1. `ProfileScreen`'s `AlertDialog` confirm button's `onClick` → `viewModel.signOut()`.
 *   2. `ProfileScreen`'s `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` →
 *      `onSignedOut()`. Delete that effect and sign-out silently stops navigating anywhere while
 *      this suite, including `ProfileViewModelTest`, stays green.
 *   3. The BINDING at `ProfileNavigation.kt`'s `onSignedOut = signOutNavigation(onNavigate)` —
 *      change it to `onSignedOut = {}` and this test still passes while sign-out goes unreachable
 *      again.
 * All three need a Hilt-composed `ProfileScreen` to close, which this repo does not have a
 * harness for.
 */
class ProfileNavigationTest {
    @Test
    fun `sign-out navigates to AuthRoute`() {
        val navigated = mutableListOf<AppRoute>()

        signOutNavigation(onNavigate = navigated::add).invoke()

        assertEquals(listOf(AuthRoute), navigated)
    }
}
