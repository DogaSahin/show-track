package com.anarky.showtrack.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anarky.showtrack.core.model.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * [GroupSwitcher]'s own rendering — `EndOfListTriggerTest`'s pattern: `createComposeRule` +
 * `RobolectricTestRunner`, `sdk = 35` from this module's `src/test/resources/robolectric.properties`.
 *
 * The task brief's own two named tests, verbatim, are the first two below.
 */
@RunWith(RobolectricTestRunner::class)
class GroupSwitcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** E-K: a switcher over a single option is noise. */
    @Test
    fun `a user in one group sees no switcher`() {
        composeRule.setContent {
            GroupSwitcher(groups = listOf(ALPHA), activeGroupId = ALPHA.id, onGroupSelected = {})
        }

        composeRule.onNodeWithText(ALPHA.name).assertDoesNotExist()
    }

    /** The negative control for the test above. */
    @Test
    fun `a user in two groups sees the switcher`() {
        composeRule.setContent {
            GroupSwitcher(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id, onGroupSelected = {})
        }

        composeRule.onNodeWithText(ALPHA.name).assertIsDisplayed()
        composeRule.onNodeWithText(BETA.name).assertIsDisplayed()
    }

    /**
     * Tapping a tab other than the currently-active one invokes the callback with THAT group's id
     * — not the currently-selected one, and not always the first tab — `GroupsScreenTest`'s
     * `` `tapping a group invokes onGroupClick for that group, not the first one` `` discrimination
     * technique: a two-element fixture, tapping the row that is NOT already active and NOT first.
     */
    @Test
    fun `tapping an inactive tab selects that group`() {
        var selected: String? = null
        composeRule.setContent {
            GroupSwitcher(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id, onGroupSelected = { selected = it })
        }

        composeRule.onNodeWithText(BETA.name).performClick()

        assertEquals(BETA.id, selected)
    }

    /** Tapping the tab that is ALREADY active still reports it — the row is not disabled. */
    @Test
    fun `tapping the already-active tab still invokes the callback`() {
        var selected: String? = null
        composeRule.setContent {
            GroupSwitcher(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id, onGroupSelected = { selected = it })
        }

        composeRule.onNodeWithText(ALPHA.name).performClick()

        assertEquals(ALPHA.id, selected)
    }

    /** Zero groups is the same "nothing to switch" case one group is — must not crash either. */
    @Test
    fun `a user in no groups sees no switcher`() {
        var selected: String? = null
        composeRule.setContent {
            GroupSwitcher(groups = emptyList(), activeGroupId = "unused", onGroupSelected = { selected = it })
        }

        composeRule.onNodeWithText(ALPHA.name).assertDoesNotExist()
        assertNull(selected)
    }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
    }
}
