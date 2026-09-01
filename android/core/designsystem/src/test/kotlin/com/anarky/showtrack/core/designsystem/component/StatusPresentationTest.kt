package com.anarky.showtrack.core.designsystem.component

import androidx.compose.ui.test.junit4.createComposeRule
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * label() reads from res/values/strings.xml now, so a test asserting it returns "Watching" would
 * only be re-asserting the resource file. The mistake worth guarding against is a copy-paste that
 * maps two [UserMediaStatus] values onto the same string resource — every status must still
 * resolve to its own, distinct label.
 */
@RunWith(RobolectricTestRunner::class)
class StatusPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every status maps to a distinct label`() {
        val labels = mutableListOf<String>()
        composeRule.setContent {
            labels += UserMediaStatus.entries.map { it.label() }
        }
        composeRule.waitForIdle()

        assertEquals(UserMediaStatus.entries.size, labels.toSet().size)
    }
}
