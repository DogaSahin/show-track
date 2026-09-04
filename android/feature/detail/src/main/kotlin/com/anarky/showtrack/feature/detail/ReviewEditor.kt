package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.model.GroupFailure

/**
 * The "write / edit a review" door (task 9c.7, E-G, design doc §3.6) — a TITLE-scoped action, not
 * a group one (E-G's own reasoning: `POST /v1/reviews` takes a `media_id` and is scoped to the
 * caller's own library, not to any group). Renders unconditionally in [DetailContent], independent
 * of whether a group is even active — only the ALREADY-WRITTEN reviews below it, in
 * [GroupSection]'s own [GroupSectionState.Loaded.reviews], are group-scoped.
 *
 * [reviewEditor] [ReviewEditorState.Closed] renders a single door button; [ReviewEditorState.Open]
 * renders the form. The form's own body/spoiler-flag draft is LOCAL `remember`ed state, seeded
 * ONCE from [ReviewEditorState.Open.seedBody]/`.seedContainsSpoilers` — that type's own KDoc has
 * the full reasoning for why the draft is not routed through the ViewModel keystroke by keystroke;
 * `GroupsDialogs.CreateGroupDialog`'s identical technique, one feature over.
 */
@Composable
internal fun ReviewEditorSection(
    reviewEditor: ReviewEditorState,
    onOpen: () -> Unit,
    onSave: (body: String, containsSpoilers: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (reviewEditor) {
        ReviewEditorState.Closed ->
            TextButton(onClick = onOpen, modifier = modifier) {
                Text(text = stringResource(R.string.detail_review_write_button))
            }

        is ReviewEditorState.Open ->
            ReviewEditorForm(editor = reviewEditor, onSave = onSave, onCancel = onCancel, modifier = modifier)
    }
}

/**
 * [ReviewEditorState.Open.reviewId] drives only the heading text here — "write" versus "edit" —
 * never which button is wired to which callback: [onSave] is the same callback either way, since
 * [DetailViewModel.saveReview] itself is the one place that decides POST versus PATCH, from the
 * SAME field. Duplicating that branch here would be a second place the two could disagree.
 */
@Composable
private fun ReviewEditorForm(
    editor: ReviewEditorState.Open,
    onSave: (body: String, containsSpoilers: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable, not a plain remember: ReviewEditorState.Open's own KDoc — seeded once, from
    // whichever review DetailViewModel.openReviewEditor resolved at open time, never rewritten by a
    // later recomposition (a saving/error-only field change on `editor` must not reset what the
    // reader has already typed).
    var body by rememberSaveable { mutableStateOf(editor.seedBody) }
    var containsSpoilers by rememberSaveable { mutableStateOf(editor.seedContainsSpoilers) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space = 8.dp)) {
        Text(
            text =
                stringResource(
                    if (editor.reviewId == null) {
                        R.string.detail_review_write_heading
                    } else {
                        R.string.detail_review_edit_heading
                    },
                ),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text(text = stringResource(R.string.detail_review_body_label)) },
            enabled = !editor.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        // The Row itself is the tap target, not just the Checkbox — Material's own recommended
        // shape for a checkbox with a label, and what makes onNodeWithText(spoiler_label) a valid
        // way to toggle it in a test: Checkbox.onCheckedChange is null here on purpose, so the
        // click is handled exactly ONCE, by the Row, rather than racing two handlers on one tap.
        Row(
            modifier =
                Modifier.clickable(enabled = !editor.saving) { containsSpoilers = !containsSpoilers },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space = 4.dp),
        ) {
            Checkbox(checked = containsSpoilers, onCheckedChange = null, enabled = !editor.saving)
            Text(text = stringResource(R.string.detail_review_spoiler_label))
        }
        if (editor.error != null) {
            Text(
                text = stringResource(editor.error.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            Button(onClick = { onSave(body, containsSpoilers) }, enabled = !editor.saving) {
                Text(text = stringResource(R.string.detail_review_save_button))
            }
            TextButton(onClick = onCancel, enabled = !editor.saving) {
                Text(text = stringResource(R.string.detail_review_cancel_button))
            }
        }
    }
}

/**
 * [ReviewSaveError.BodyRequired]/[ReviewSaveError.BodyTooLong] never reach the server — this
 * type's own KDoc — so their copy is entirely LOCAL and needs no [GroupFailure] mapping.
 * [ReviewSaveError.Remote] delegates to [reviewSaveFailureMessageRes], a SEPARATE exhaustive-over-
 * [GroupFailure] mapping from `GroupSection.kt`'s own `GroupFailure.messageRes()` — a second
 * extension function of the identical name and receiver type in the same module would be an
 * unresolvable overload ambiguity, and the two describe different endpoints' failures regardless
 * (propose/progress/reviews-fetch there, review save here), so they earn separate copy rather than
 * sharing a mapping neither call site's failures fully overlap with.
 */
internal fun ReviewSaveError.messageRes(): Int =
    when (this) {
        ReviewSaveError.BodyRequired -> R.string.detail_review_error_body_required
        ReviewSaveError.BodyTooLong -> R.string.detail_review_error_body_too_long
        is ReviewSaveError.Remote -> failure.reviewSaveFailureMessageRes()
    }

/**
 * Exhaustive over all EIGHT [GroupFailure] cases, no `else` — `GroupSection.kt`'s own discipline,
 * so a ninth case added later fails this file to COMPILE rather than silently falling through.
 *
 * Genuinely reachable from [DetailViewModel.saveReview]/`.handleCreateFailure`:
 * [GroupFailure.Network]; [GroupFailure.NoSuchTitle] (`POST /v1/reviews`'s 404, an invalid
 * `media_id` — defensive, since Detail always has a real one once loaded); [GroupFailure.NoSuchEntry]
 * (`PATCH /v1/reviews/{id}`'s 404 — the review was deleted between load and save, a genuine
 * if rare race); [GroupFailure.AlreadyReviewed] (ONLY the unresolved-locally fallback —
 * [DetailViewModel.saveReview]'s own KDoc — every RESOLVABLE 409 is retried transparently and
 * never reaches this mapping at all). [GroupFailure.NotPermitted]/[GroupFailure.NotAMember]/
 * [GroupFailure.InvalidInviteCode] describe endpoints neither review call ever reaches (403/404
 * shapes belonging to owner-only and group-membership routes) — folded into the same generic copy
 * as [GroupFailure.Unknown].
 */
private fun GroupFailure.reviewSaveFailureMessageRes(): Int =
    when (this) {
        GroupFailure.Network -> R.string.detail_review_error_network
        GroupFailure.NoSuchTitle -> R.string.detail_review_error_no_such_title
        GroupFailure.NoSuchEntry -> R.string.detail_review_error_no_such_entry
        is GroupFailure.AlreadyReviewed -> R.string.detail_review_error_already_reviewed_unresolved
        GroupFailure.NotPermitted,
        GroupFailure.NotAMember,
        GroupFailure.InvalidInviteCode,
        is GroupFailure.Unknown,
        -> R.string.detail_review_error_unknown
    }
