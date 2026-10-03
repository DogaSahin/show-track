package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The owner's invite strip: what it says for a live, an expired and an unknown code, that only the
 * owner gets it (and only the owner's page asks for the code), and that TalkBack hears the code
 * one character at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp")
class GroupInviteStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the owner sees the code, how long it works, and the page asks for it`() {
        var loads = 0
        setScreen(
            currentUserId = OWNER.userId,
            invite =
                ready(
                    expiresIn = Duration.ofDays(5).plusHours(2),
                ),
            onLoadInvite = {
                loads++
            },
        )

        composeRule.onNodeWithText("K7Q2-MX9P-4TBH", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Invite code · works 5 more days", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithContentDescription(
                "Invite code K 7 Q 2 M X 9 P 4 T B H, Invite code · works 5 more days",
            ).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.groups_invite_share)).assertExists()
        assertEquals(1, loads)
    }

    @Test
    fun `an expired code says so and offers a new one`() {
        var rotateDialogOpened = false
        setScreen(
            currentUserId = OWNER.userId,
            invite = ready(expiresIn = Duration.ofDays(-2)),
            onRotateDialogOpened = { rotateDialogOpened = true },
        )

        composeRule.onNodeWithText("Expired", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_new_code)).performClick()

        assertTrue(rotateDialogOpened)
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_confirm_message)).assertExists()
    }

    @Test
    fun `without a code the strip falls back to inviting people`() {
        setScreen(currentUserId = OWNER.userId, invite = InviteState.Unavailable)

        composeRule.onNodeWithText(context.getString(R.string.groups_invite_fallback)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_new_code)).assertExists()
    }

    /** While the code loads there is nothing to act on: offering New code would kill the shared one. */
    @Test
    fun `a loading strip offers no new code`() {
        setScreen(currentUserId = OWNER.userId, invite = InviteState.Unknown)

        composeRule.onNodeWithText(context.getString(R.string.groups_invite_loading)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_new_code)).assertDoesNotExist()
    }

    @Test
    fun `a member gets no strip and the page never asks for the code`() {
        var loads = 0
        setScreen(
            currentUserId = MEMBER.userId,
            invite = ready(expiresIn = Duration.ofDays(5)),
            onLoadInvite = { loads++ },
        )

        composeRule.onNodeWithText("K7Q2-MX9P-4TBH", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_fallback)).assertDoesNotExist()
        assertEquals(0, loads)
    }

    private fun ready(expiresIn: Duration) =
        InviteState.Ready(
            GroupWithInvite(
                group = Group(id = "group-1", name = "Home", createdAt = Instant.parse("2026-08-28T10:15:30Z")),
                inviteCode = "K7Q2MX9P4TBH",
                expiresAt = Instant.now().plus(expiresIn),
            ),
        )

    private fun setScreen(
        currentUserId: String,
        invite: InviteState,
        onLoadInvite: () -> Unit = {},
        onRotateDialogOpened: () -> Unit = {},
    ) {
        composeRule.setContent {
            GroupDetailScreen(
                groupId = "group-1",
                groupName = "Home",
                state = GroupDetailUiState.Success(members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                currentUserId = currentUserId,
                invite = invite,
                onLoadInvite = onLoadInvite,
                onBack = {},
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onRotateDialogOpened = onRotateDialogOpened,
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }
    }

    private companion object {
        val OWNER =
            GroupMember(
                userId = "user-owner",
                username = "alex",
                role = GroupRole.OWNER,
                joinedAt = Instant.parse("2025-03-01T10:00:00Z"),
            )
        val MEMBER =
            GroupMember(
                userId = "user-member",
                username = "sam",
                role = GroupRole.MEMBER,
                joinedAt = Instant.parse("2025-04-01T10:00:00Z"),
            )
    }
}
