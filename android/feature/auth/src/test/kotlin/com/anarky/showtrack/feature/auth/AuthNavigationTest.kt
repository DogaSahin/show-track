package com.anarky.showtrack.feature.auth

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B3, task 9b.6 fix round: the decision line `AuthNavigation.kt` actually ships on — which route a
 * successful authentication resolves to — had NO test anywhere in the repository before this file.
 * `authEntry`'s lambda constructs `AuthScreen` WITHOUT passing `viewModel`, evaluating its
 * `hiltViewModel()` default, and `:feature:auth` has no Hilt test harness, so composing `authEntry`
 * itself to observe the decision was not an option — [authenticatedNavigation] is pulled out
 * specifically so a plain unit test can drive it instead, the same pattern
 * `ProfileNavigation.kt`'s `signOutNavigation`/`importNavigation` already use for the identical
 * reason.
 *
 * Confirmed as a real gap, not a theoretical one: inverting the condition on
 * `authenticatedNavigation`'s one line, or reverting it to the pre-task-9b.6
 * `onNavigate(LibraryRoute)` regardless of `isNewAccount`, left the ENTIRE existing suite green —
 * nothing in the repository referenced [authEntry] or [authenticatedNavigation] at all. See this
 * task's report for the quoted failure this file now produces against both mutations.
 */
class AuthNavigationTest {
    @Test
    fun `a fresh registration navigates to ImportRoute`() {
        val navigated = mutableListOf<AppRoute>()

        authenticatedNavigation(onNavigate = navigated::add).invoke(true)

        assertEquals(listOf(ImportRoute), navigated)
    }

    @Test
    fun `a login navigates to LibraryRoute`() {
        val navigated = mutableListOf<AppRoute>()

        authenticatedNavigation(onNavigate = navigated::add).invoke(false)

        assertEquals(listOf(LibraryRoute), navigated)
    }
}
