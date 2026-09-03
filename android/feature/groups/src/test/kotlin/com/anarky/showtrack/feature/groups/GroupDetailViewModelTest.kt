package com.anarky.showtrack.feature.groups

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [GroupDetailViewModel] exercised against a fake [com.anarky.showtrack.core.data.repository.GroupRepository]
 * — [GroupsViewModelTest]'s identical shape, one screen over.
 *
 * Robolectric, `DetailViewModelTest`'s identical reasoning (that class's own KDoc, quoted here
 * since this is the same failure mode): [GroupDetailViewModel]'s constructor calls
 * `SavedStateHandle.toRoute<GroupDetailRoute>()`, which builds an intermediate `android.os.Bundle`
 * internally — unmocked, and therefore a crash, on a bare JVM. `sdk=35` is pinned module-wide in
 * `src/test/resources/robolectric.properties` (Robolectric ships no shadow jar for 36);
 * `application = Application::class` avoids standing up `ShowTrackApplication`'s `@HiltAndroidApp`
 * component, which this test needs neither DataStore nor the Keystore from.
 *
 * `GroupDetailViewModel`'s `init { refresh() }` means every test below observes the CONSTRUCTOR's
 * own load — `viewModel(repository)` alone already schedules the first `refresh()`, so
 * `advanceUntilIdle()` right after construction is what stands in for the resume `GroupsViewModelTest`
 * calls explicitly, mirroring how `DetailViewModelTest` (this class's own closer analogue) handles
 * its identical `init { load() }`.
 *
 * **What this class deliberately does NOT pin:** owner-only rendering (E-F) and "leave is offered to
 * everyone, remove never for yourself" are RENDERING decisions — `GroupDetailScreenTest` pins those,
 * driving the stateless `GroupDetailScreen` overload directly (Global Constraints: "if a behaviour is
 * a rendering decision, a ViewModel test cannot pin it").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeGroupRepository): GroupDetailViewModel =
        GroupDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("groupId" to GROUP_ID)),
            repository = repository,
        )

    @Test
    fun `refresh loads the members and the signed-in user's own id`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER, MEMBER),
                    currentUserIdResult = OWNER.userId,
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER, MEMBER), currentUserId = OWNER.userId),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failed initial load with nothing on screen produces Error`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(membersFailure = GroupFailure.Network)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)
        }

    /**
     * The settled refresh shape (Global Constraints), `GroupsViewModelTest`'s identical test one
     * screen over: a background reload must not blank an already-populated screen to a spinner
     * while it is still in flight.
     */
    @Test
    fun `a reload over an already-loaded screen keeps the members, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER), currentUserId = OWNER.userId),
                viewModel.state.value,
            )

            repository.membersResult = listOf(OWNER, MEMBER)
            repository.membersGate = CompletableDeferred()
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD member list, and still Success — never Loading — while the network
            // round trip this refresh triggered is genuinely still in flight.
            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER), currentUserId = OWNER.userId),
                viewModel.state.value,
            )

            repository.membersGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER, MEMBER), currentUserId = OWNER.userId),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failed reload over an already-loaded screen marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.membersFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER), currentUserId = OWNER.userId, isStale = true),
                viewModel.state.value,
            )
        }

    /** The brief's fourth named test, verbatim. */
    @Test
    fun `rotating replaces the displayed code`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.rotateResult = INVITE
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
            assertEquals(GroupDetailActionState(), viewModel.actionState.value)
        }

    /**
     * The negative control: a failed rotate must leave [GroupDetailUiState.Success.rotatedInvite]
     * unset (never a stale or partial code) and surface the failure on
     * [GroupDetailActionState.rotateError] instead — [GroupsViewModelTest]'s identical shape for
     * `createGroup`.
     */
    @Test
    fun `a failed rotate surfaces the error and leaves no code on screen`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.rotateFailure = GroupFailure.NotPermitted
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertNull((viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.rotateError)
        }

    @Test
    fun `rotate is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    currentUserIdResult = OWNER.userId,
                    rotateResult = INVITE,
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.rotateInvite()
            viewModel.rotateInvite()
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(1, repository.rotateCalls)
        }

    @Test
    fun `leaving calls removeMember with the signed-in user's own id and sets left`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to OWNER.userId), repository.removeMemberCalls)
            assertTrue(viewModel.left.value)
        }

    @Test
    fun `a failed leave surfaces the error and does not set left`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.removeMemberFailure = GroupFailure.Network
            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)
            assertTrue("a failed leave must not navigate away", !viewModel.left.value)
        }

    @Test
    fun `leave is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.leaveGroup()
            viewModel.leaveGroup()
            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(1, repository.removeMemberCalls.size)
        }

    /**
     * Mutation-critical negative control: a `removeMember` that always targeted the CALLER's own
     * id (or the first member in the list) rather than the argument it was given would pass every
     * OTHER test in this file, since [MEMBER] is not [OWNER]. Two members in the fixture, removing
     * the SECOND one, is what actually discriminates "reads the argument" from "reads something
     * fixed" — `GroupsEntryHiltTest`'s own KDoc names this exact failure shape.
     */
    @Test
    fun `removing a member calls removeMember with that member's id, not the caller's own`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to MEMBER.userId), repository.removeMemberCalls)
        }

    /**
     * `GroupDetailViewModel.removeMember`'s own KDoc: [GroupDetailActionState.removingUserId] stays
     * set through the WHOLE round trip, including the reload, not just the delete call — a fast
     * double-tap in that window must still be dropped. [membersGate] is what makes the in-flight
     * reload observable rather than resolving synchronously and hiding the window entirely.
     */
    @Test
    fun `removingUserId stays set through the reload, not just the delete call`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.membersGate = CompletableDeferred()
            repository.membersResult = listOf(OWNER)
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            // The DELETE itself has completed (it needs no gate to observe), but the follow-up
            // GET /members this reload issues is still suspended — removingUserId must still be set.
            assertEquals(MEMBER.userId, viewModel.actionState.value.removingUserId)

            // A second remove call for a DIFFERENT member must still be dropped while the first
            // one's reload is in flight.
            viewModel.removeMember(OWNER.userId)
            advanceUntilIdle()
            assertEquals(1, repository.removeMemberCalls.size)

            repository.membersGate?.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.actionState.value.removingUserId)
            assertEquals(listOf(OWNER), (viewModel.state.value as GroupDetailUiState.Success).members)
        }

    @Test
    fun `a failed remove surfaces the error, clears removingUserId, and leaves the member list untouched`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.removeMemberFailure = GroupFailure.NotPermitted
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertNull(viewModel.actionState.value.removingUserId)
            assertEquals(listOf(OWNER, MEMBER), (viewModel.state.value as GroupDetailUiState.Success).members)
        }

    @Test
    fun `remove is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.removeMember(MEMBER.userId)
            viewModel.removeMember(MEMBER.userId)
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(1, repository.removeMemberCalls.size)
        }

    /**
     * Fix-round-2 lesson (`GroupsViewModel.clearCreateError`'s own KDoc), applied proactively:
     * three SEPARATE channels (decision C-S), so clearing one must never touch the other two.
     */
    @Test
    fun `clearRotateError, clearRemoveError and clearLeaveError each clear only their own channel`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.rotateFailure = GroupFailure.NotPermitted
            viewModel.rotateInvite()
            advanceUntilIdle()
            repository.removeMemberFailure = GroupFailure.NotPermitted
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()
            repository.removeMemberFailure = GroupFailure.Network
            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.rotateError)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearRotateError()
            assertNull(viewModel.actionState.value.rotateError)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearRemoveError()
            assertNull(viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearLeaveError()
            assertNull(viewModel.actionState.value.leaveError)
        }

    @Test
    fun `dismissRotatedInvite clears the rotated code and nothing else`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    currentUserIdResult = OWNER.userId,
                    rotateResult = INVITE,
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            viewModel.rotateInvite()
            advanceUntilIdle()
            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)

            viewModel.dismissRotatedInvite()

            val result = viewModel.state.value as GroupDetailUiState.Success
            assertNull(result.rotatedInvite)
            assertEquals(listOf(OWNER), result.members)
        }

    private companion object {
        const val GROUP_ID = "group-1"

        fun member(
            id: String,
            username: String,
            role: GroupRole,
        ) = GroupMember(userId = id, username = username, role = role, joinedAt = Instant.parse("2026-08-28T10:15:30Z"))

        val OWNER = member(id = "user-owner", username = "alex", role = GroupRole.OWNER)
        val MEMBER = member(id = "user-member", username = "sam", role = GroupRole.MEMBER)
        val INVITE =
            GroupWithInvite(
                group = Group(id = GROUP_ID, name = "Watch Party", createdAt = Instant.parse("2026-08-28T10:15:30Z")),
                inviteCode = "NEWCODE0000000000000",
                expiresAt = Instant.parse("2026-09-10T00:00:00Z"),
            )
    }
}
