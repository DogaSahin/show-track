package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.SearchRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard `LibraryScreenTest` alone cannot provide. That test pins icon tap →
 * `onSearchClick`; this pins `onSearchClick` → `onNavigate(SearchRoute)` — the wiring line inside
 * `libraryEntry`'s `composable<LibraryRoute> { }` block that a Compose test cannot reach without
 * composing `LibraryScreen`'s stateful overload, which needs Hilt to resolve `LibraryViewModel`
 * and this module has no Hilt test harness. A plain JUnit test on the extracted [searchNavigation]
 * function needs neither Compose nor Hilt. Together the two tests close Gap 1's exact failure
 * mode: change either the icon's `onClick` or this mapping back to a no-op, and one of them fails.
 */
class LibraryNavigationTest {
    @Test
    fun `the search action navigates to SearchRoute`() {
        val navigated = mutableListOf<AppRoute>()

        searchNavigation(onNavigate = navigated::add).invoke()

        assertEquals(listOf(SearchRoute), navigated)
    }
}
