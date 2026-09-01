package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.SearchRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The regression guard `LibraryScreenTest` alone cannot provide. That test pins icon tap →
 * `onSearchClick` (the parameter); this pins [searchNavigation] itself → `onNavigate(SearchRoute)`.
 * A plain JUnit test on the extracted function needs neither Compose nor Hilt, unlike composing
 * `LibraryScreen`'s stateful overload, which resolves `LibraryViewModel` through `hiltViewModel()`
 * — and this module has no Hilt test harness.
 *
 * What neither test pins: the BINDING at `LibraryNavigation.kt`'s
 * `onSearchClick = searchNavigation(onNavigate)` — that nothing here composes `libraryEntry`
 * itself means nothing observes that this particular argument is wired to this particular
 * function. Change that one line to `onSearchClick = {}` and both this test and
 * `LibraryScreenTest` stay green while the search screen goes unreachable again. Closing that
 * needs a Hilt test harness this repo does not have.
 */
class LibraryNavigationTest {
    @Test
    fun `the search action navigates to SearchRoute`() {
        val navigated = mutableListOf<AppRoute>()

        searchNavigation(onNavigate = navigated::add).invoke()

        assertEquals(listOf(SearchRoute), navigated)
    }
}
