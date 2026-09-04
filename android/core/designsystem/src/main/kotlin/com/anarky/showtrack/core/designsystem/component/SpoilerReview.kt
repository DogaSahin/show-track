package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.R
import com.anarky.showtrack.core.model.Review

/**
 * One group review, with [Review.containsSpoilers] honoured (decision E-J, task 9c.6): that flag
 * is the ONLY spoiler machinery in the entire design (§5.3 of the phase design doc) — the server
 * carries it as a bare boolean and does nothing else with it, so a client that renders the body
 * regardless makes the flag a column nobody reads rather than a feature.
 *
 * **The collapsed state renders no [Review.body] composable at all** — not a transparent one, not
 * a zero-height one, not one behind a blur `Modifier`. Those would all still put the text into the
 * composition (and, for a real device, the accessibility tree and the view hierarchy a screenshot
 * or a compromised overlay can read), which is exactly the leak `containsSpoilers` exists to
 * prevent. The `when`-shaped branch below is what makes "collapsed" and "revealed" two genuinely
 * different subtrees rather than one subtree with a visibility flag on it.
 *
 * [revealed] is keyed on [Review.id] via `rememberSaveable` — a fresh `Review` (a different id)
 * always starts collapsed regardless of what the PREVIOUS row at this same call site was showing
 * (`LazyColumn` re-using composition slots across scroll would otherwise leak one row's "already
 * tapped" state onto a different review), and `rememberSaveable` (not a plain `remember`) survives
 * a configuration change so a reveal a reader just tapped does not silently re-hide itself on
 * rotation.
 *
 * Reveal is one-way for the lifetime of this composition — there is no "hide it again" control.
 * The backend flag means "this review talks about plot events the reader may not have reached
 * yet," not "keep re-asking"; once a reader has chosen to see it, re-collapsing on every
 * recomposition would be surprising, not protective.
 *
 * **The reveal button carries its own `contentDescription`** (fix round 2, coordinator finding
 * 6) — naming the review's author, e.g. "Show spoiler review by alice" — so a screen reader can
 * tell N reviews' worth of otherwise-identical "Show spoiler" buttons apart. This does NOT remove
 * `SemanticsProperties.Text` from the button's merged node (that property still comes from the
 * child [Text] composable below), so [SpoilerReviewTest]'s own `onNodeWithText` matchers on the
 * visible "Show spoiler" label keep working unchanged; a real accessibility service prefers the
 * explicit `ContentDescription` over the plain `Text` when both are present on the same node,
 * which is what makes this the announcement a screen reader actually reads. Verified on the JVM
 * with `onNodeWithContentDescription`, not merely asserted.
 */
@Composable
fun SpoilerReview(
    review: Review,
    modifier: Modifier = Modifier,
) {
    var revealed by rememberSaveable(review.id) { mutableStateOf(false) }
    val revealDescription = stringResource(R.string.spoiler_review_reveal_by, review.author.username)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(all = 12.dp),
            verticalArrangement = Arrangement.spacedBy(space = 4.dp),
        ) {
            Text(text = review.author.username, style = MaterialTheme.typography.labelLarge)
            if (review.containsSpoilers && !revealed) {
                TextButton(
                    onClick = { revealed = true },
                    modifier =
                        Modifier.semantics {
                            contentDescription = revealDescription
                        },
                ) {
                    Text(text = stringResource(R.string.spoiler_review_reveal))
                }
            } else {
                Text(text = review.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
