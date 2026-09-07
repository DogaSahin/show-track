package com.anarky.showtrack.core.designsystem.component

import androidx.compose.ui.test.junit4.createComposeRule
import com.anarky.showtrack.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for the raw-enum-constant bug (decision C-E): `SearchScreen`'s degraded-provider
 * banner used to interpolate `MediaSource.name` directly (`"ANILIST isn't responding right now"`).
 * `displayName()` reads from res/values/strings.xml now.
 *
 * Only [MediaSource.ANILIST] gets an inequality assertion against its raw `name` — `TMDB`'s brand
 * name and its enum constant are coincidentally the same string ("TMDB"), so asserting every value
 * differs from its own `name` would be wrong, not just redundant, for that one. Distinctness across
 * both values is still pinned (the `StatusPresentationTest` shape) — the copy-paste mistake worth
 * guarding against is two sources resolving to the same label.
 */
@RunWith(RobolectricTestRunner::class)
class MediaSourcePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every source maps to a distinct label`() {
        val labels = mutableListOf<String>()
        composeRule.setContent {
            labels += MediaSource.entries.map { it.displayName() }
        }
        composeRule.waitForIdle()

        assertEquals(MediaSource.entries.size, labels.toSet().size)
    }

    @Test
    fun `AniList resolves to its brand name, not the raw enum constant`() {
        var label = ""
        composeRule.setContent {
            label = MediaSource.ANILIST.displayName()
        }
        composeRule.waitForIdle()

        assertEquals("AniList", label)
        assertNotEquals(MediaSource.ANILIST.name, label)
    }
}
