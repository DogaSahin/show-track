package com.anarky.showtrack.core.designsystem.component

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.Review
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * [SpoilerReview]'s own rendering — the task 9c.6 brief's own two named tests, verbatim, are the
 * first two below. `createComposeRule` + `RobolectricTestRunner`, `sdk = 35` from this module's own
 * `src/test/resources/robolectric.properties` — `GroupSwitcherTest`'s identical setup.
 */
@RunWith(RobolectricTestRunner::class)
class SpoilerReviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * E-J's whole point. Asserts the BODY TEXT is absent before the tap — not merely that a "show
     * spoiler" control exists, which would pass against a component that renders the body
     * underneath that control. [assertDoesNotExist] on the exact body string is what a transparent-
     * or zero-height-body implementation would fail, and a "control exists" assertion would not.
     */
    @Test
    fun `a spoiler review is unreadable until tapped`() {
        composeRule.setContent {
            SpoilerReview(review = SPOILER_REVIEW)
        }

        composeRule.onNodeWithText(SPOILER_REVIEW.body).assertDoesNotExist()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.spoiler_review_reveal)).performClick()

        composeRule.onNodeWithText(SPOILER_REVIEW.body).assertIsDisplayed()
    }

    /** The negative control: no spoiler flag, no tap needed, the body is on screen immediately. */
    @Test
    fun `a non-spoiler review is readable without tapping`() {
        composeRule.setContent {
            SpoilerReview(review = PLAIN_REVIEW)
        }

        composeRule.onNodeWithText(PLAIN_REVIEW.body).assertIsDisplayed()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.spoiler_review_reveal)).assertDoesNotExist()
    }

    /** The author's own username renders regardless of the spoiler flag — only the body is gated. */
    @Test
    fun `the author's username is visible even while the body is collapsed`() {
        composeRule.setContent {
            SpoilerReview(review = SPOILER_REVIEW)
        }

        composeRule.onNodeWithText(SPOILER_REVIEW.author.username).assertIsDisplayed()
    }

    /**
     * Fix round 2, coordinator finding 6: N reviews render N otherwise-identical "Show spoiler"
     * buttons — a screen reader cannot tell them apart on the visible label alone. The reveal
     * button's own `contentDescription` names the review's author; verified directly with
     * `onNodeWithContentDescription`, not merely asserted to exist. This does NOT remove the
     * button's `Text` semantics property (the child [Text] composable still contributes it), so
     * the OTHER two tests above — both matching on `onNodeWithText(spoiler_review_reveal)` —
     * keep passing unchanged.
     */
    @Test
    fun `the reveal button names the review's author, for a screen reader`() {
        composeRule.setContent {
            SpoilerReview(review = SPOILER_REVIEW)
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.spoiler_review_reveal_by, SPOILER_REVIEW.author.username),
            ).assertIsDisplayed()
    }

    private companion object {
        val AUTHOR = GroupActor(id = "user-1", username = "alice")
        val SPOILER_REVIEW =
            Review(
                id = "review-1",
                author = AUTHOR,
                mediaId = "media-1",
                body = "The captain dies in episode nine.",
                containsSpoilers = true,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val PLAIN_REVIEW =
            Review(
                id = "review-2",
                author = AUTHOR,
                mediaId = "media-1",
                body = "Great pacing all the way through.",
                containsSpoilers = false,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
    }
}
