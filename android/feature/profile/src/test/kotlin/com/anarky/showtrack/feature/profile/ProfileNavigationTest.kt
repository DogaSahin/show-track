package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard `ProfileViewModelTest` alone cannot provide. That test pins the button →
 * `signOut()` → `AuthRepository.logout()` half; this pins `onSignedOut` → `onNavigate(AuthRoute)`
 * — the wiring line inside `profileEntry`'s `composable<ProfileRoute> { }` block that a Compose
 * test cannot reach without composing `ProfileScreen`, which needs Hilt to resolve
 * `ProfileViewModel` and this module has no Hilt test harness. A plain JUnit test on the extracted
 * [signOutNavigation] function needs neither Compose nor Hilt. Together the two tests close Gap
 * 2's exact failure mode: change either the confirm button's `onClick` or this mapping back to a
 * no-op, and one of them fails.
 */
class ProfileNavigationTest {
    @Test
    fun `sign-out navigates to AuthRoute`() {
        val navigated = mutableListOf<AppRoute>()

        signOutNavigation(onNavigate = navigated::add).invoke()

        assertEquals(listOf(AuthRoute), navigated)
    }
}
