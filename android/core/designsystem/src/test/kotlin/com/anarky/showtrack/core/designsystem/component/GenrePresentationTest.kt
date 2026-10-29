package com.anarky.showtrack.core.designsystem.component

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GenrePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Mirrors backend CANONICAL_GENRES; a code added there needs a string here. */
    private val canonical =
        listOf(
            "action",
            "adventure",
            "comedy",
            "crime",
            "documentary",
            "drama",
            "ecchi",
            "family",
            "fantasy",
            "horror",
            "kids",
            "magical_girl",
            "mecha",
            "music",
            "mystery",
            "psychological",
            "reality",
            "romance",
            "sci_fi",
            "slice_of_life",
            "soap",
            "sports",
            "supernatural",
            "talk",
            "thriller",
            "war",
            "western",
        )

    @Test
    fun `every canonical genre code has its own string resource`() {
        canonical.forEach { assertNotNull("no resource for $it", genreLabelRes(it)) }
        assertEquals(canonical.size, canonical.mapNotNull { genreLabelRes(it) }.toSet().size)
    }

    @Test
    fun `unknown code has no resource`() {
        assertNull(genreLabelRes("space_opera"))
    }

    @Test
    fun `known code reads as its display name`() {
        var label = ""
        composeRule.setContent { label = genreLabel("sci_fi") }
        composeRule.waitForIdle()
        assertEquals("Sci-Fi", label)
    }

    @Test
    fun `unknown code falls back to title case`() {
        var label = ""
        composeRule.setContent { label = genreLabel("space_opera") }
        composeRule.waitForIdle()
        assertEquals("Space Opera", label)
    }
}
