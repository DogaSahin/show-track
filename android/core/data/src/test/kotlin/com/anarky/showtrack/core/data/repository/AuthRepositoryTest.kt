package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.model.AuthFailure
import com.anarky.showtrack.core.model.PushNotification
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.auth.TokenPair
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.FeedPageDto
import com.anarky.showtrack.core.network.dto.GroupDto
import com.anarky.showtrack.core.network.dto.GroupWithInviteDto
import com.anarky.showtrack.core.network.dto.ImportAniListRequest
import com.anarky.showtrack.core.network.dto.ImportSummaryDto
import com.anarky.showtrack.core.network.dto.JoinGroupRequestDto
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.LibraryStatsDto
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.MemberDto
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.TokenPairDto
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Builds an `HttpException` the way Retrofit itself does, for a non-2xx response. */
private fun httpError(code: Int): HttpException = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

/**
 * Robolectric for the same reason as [com.anarky.showtrack.core.data.push.PushRepositoryImplTest]:
 * a caught push/revoke failure is logged through `android.util.Log`, which a plain JVM test
 * answers with "not mocked" — and THROWS, which would fail `a push failure does not fail the
 * login` for the opposite of the reason it exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthRepositoryTest {
    @Test
    fun `register creates the account and then logs in`() =
        runTest {
            val api = FakeAuthApi()
            val store = FakeTokenStore()
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), store, FakePush())

            repository.register("someone", "a@example.com", "hunter2hunter2", "CODE")

            assertEquals(listOf("register", "login"), api.calls)
            assertEquals(TokenPair("access-1", "refresh-1"), store.saved)
        }

    @Test
    fun `an account created but not logged in reports itself as exactly that`() =
        runTest {
            // C-M: calling this "registration failed" would send the user back to a form that
            // now answers "email already taken", with nothing left to try.
            val api = FakeAuthApi(loginFailure = IOException("offline"))
            val store = FakeTokenStore()
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), store, FakePush())

            val failure =
                runCatching {
                    repository.register("someone", "a@example.com", "hunter2hunter2", "CODE")
                }.exceptionOrNull()

            assertTrue(failure is RegisteredButNotLoggedIn)
            assertNull(store.saved)
        }

    @Test
    fun `login registers this device for push`() =
        runTest {
            val push = FakePush()
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), FakeTokenStore(), push)

            repository.login("a@example.com", "hunter2hunter2")

            assertTrue(push.loggedIn)
        }

    @Test
    fun `a push failure does not fail the login`() =
        runTest {
            // The user typed the right password. Failing the whole login because a notification
            // target could not be created would be a lie about what went wrong.
            val store = FakeTokenStore()
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, FakePush(failure = IOException("offline")))

            repository.login("a@example.com", "hunter2hunter2")

            assertEquals(TokenPair("access-1", "refresh-1"), store.saved)
        }

    @Test
    fun `logout deletes the push target before it clears the tokens`() =
        runTest {
            // deletePushTarget is an AUTHENTICATED call. Clear first and it 401s, leaving the
            // server pushing episodes to a signed-out device. Order, not just outcome.
            // ONE shared recorder, not one list per fake: concatenating two separate lists
            // yields their declaration order, not the call order, and would pass or fail
            // regardless of what the code does.
            val calls = mutableListOf<String>()
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"), calls = calls)
            val push = FakePush(calls = calls)
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, push)

            repository.logout()

            assertEquals(listOf("push.onLoggedOut", "store.clear"), calls)
            assertFalse(repository.hasSession())
        }

    @Test
    fun `logout clears the tokens even when the push cleanup fails`() =
        runTest {
            // Unguarded, a throw here would skip revoke() and tokenStore.clear() below and leave
            // the user pressing "log out" and staying logged in — worse than login's symmetric
            // case, where a push failure must not be misreported as a login failure.
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"))
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, FakePush(failure = IOException("offline")))

            repository.logout()

            assertFalse(repository.hasSession())
        }

    @Test
    fun `a wrong password surfaces as invalid credentials`() =
        runTest {
            val api = FakeAuthApi(loginFailure = httpError(401))
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakePush())

            val failure = runCatching { repository.login("a@example.com", "wrong") }.exceptionOrNull()

            assertTrue(failure is AuthFailure.InvalidCredentials)
        }

    @Test
    fun `being offline during login surfaces as being offline`() =
        runTest {
            val api = FakeAuthApi(loginFailure = IOException("offline"))
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakePush())

            val failure = runCatching { repository.login("a@example.com", "hunter2hunter2") }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Offline)
        }

    @Test
    fun `a taken email or bad invite code surfaces as a refusal`() =
        runTest {
            // This boundary does not interpret the status — 400 (bad invite code) and 409
            // (taken email/username) both arrive as Refused, carrying whichever code the
            // server sent. Telling them apart is :feature:auth's job, done from the code.
            val api = FakeAuthApi(registerFailure = httpError(409))
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakePush())

            val failure =
                runCatching {
                    repository.register("someone", "a@example.com", "hunter2hunter2", "CODE")
                }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Refused)
            assertEquals(409, (failure as AuthFailure.Refused).statusCode)
        }

    @Test
    fun `currentUserId returns the id GET v1users me answers with`() =
        runTest {
            val showTrackApi =
                FakeShowTrackApi(meResult = UserDto("user-42", "alex", "a@b.test", "2026-09-01T00:00:00Z"))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakePush())

            val id = repository.currentUserId()

            assertEquals("user-42", id)
        }

    /**
     * Round 1 review, ruling: identity is a SESSION-lifetime fact, cached in memory rather than
     * refetched per caller — a screen that asks twice (e.g. a resume) pays for one network round
     * trip, not two. [FakeShowTrackApi.meCalls] is what makes "resolved once" distinguishable from
     * "resolved every time" (round 1 review's own minor: the OLD `FakeGroupRepository.currentUserId`
     * had no call counter at all, so no test could tell the two apart).
     */
    @Test
    fun `currentUserId is resolved once and cached for the rest of the session`() =
        runTest {
            val showTrackApi =
                FakeShowTrackApi(meResult = UserDto("user-42", "alex", "a@b.test", "2026-09-01T00:00:00Z"))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakePush())

            repository.currentUserId()
            repository.currentUserId()
            repository.currentUserId()

            assertEquals(1, showTrackApi.meCalls)
        }

    @Test
    fun `logout clears the cached currentUserId, so the next session re-resolves it`() =
        runTest {
            val showTrackApi =
                FakeShowTrackApi(meResult = UserDto("user-42", "alex", "a@b.test", "2026-09-01T00:00:00Z"))
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, store, FakePush())
            repository.currentUserId()
            assertEquals(1, showTrackApi.meCalls)

            repository.logout()
            showTrackApi.meResult = UserDto("user-99", "sam", "s@b.test", "2026-09-02T00:00:00Z")
            val secondSessionId = repository.currentUserId()

            assertEquals("user-99", secondSessionId)
            assertEquals(2, showTrackApi.meCalls)
        }

    /**
     * `logout()` is not the only way a session ends. `TokenRefreshAuthenticator` clears the token
     * store directly (`clearQuietly()`) on an unrecoverable 401 and emits `AuthEvent.LoggedOut` —
     * it never calls `AuthRepository.logout()`, so a cache cleared only by `logout()` survives
     * into whatever account signs in next. Reproduces that shape without the authenticator itself:
     * resolve as one account, clear the store the way it does, then log in as a different one.
     */
    @Test
    fun `an involuntary session clear followed by a different login re-resolves identity, not the stale cache`() =
        runTest {
            val showTrackApi =
                FakeShowTrackApi(meResult = UserDto("user-42", "alex", "a@b.test", "2026-09-01T00:00:00Z"))
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, store, FakePush())
            assertEquals("user-42", repository.currentUserId())

            // TokenRefreshAuthenticator.clearQuietly() on an unrecoverable 401 — not logout().
            store.clear()
            showTrackApi.meResult = UserDto("user-99", "sam", "s@b.test", "2026-09-02T00:00:00Z")
            repository.login("s@b.test", "hunter2hunter2")

            assertEquals("user-99", repository.currentUserId())
        }

    @Test
    fun `being offline while resolving currentUserId surfaces as being offline`() =
        runTest {
            val showTrackApi = FakeShowTrackApi(meFailure = IOException("offline"))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakePush())

            val failure = runCatching { repository.currentUserId() }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Offline)
        }

    /** The negative control: an unmapped failure (a 500, a malformed response) is Unexpected, not Offline. */
    @Test
    fun `an unmapped failure while resolving currentUserId surfaces as Unexpected`() =
        runTest {
            val showTrackApi = FakeShowTrackApi(meFailure = httpError(500))
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakePush())

            val failure = runCatching { repository.currentUserId() }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Unexpected)
        }

    @Test
    fun `hasSession is false with nothing stored and true with tokens`() =
        runTest {
            assertFalse(
                AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), FakeTokenStore(), FakePush()).hasSession(),
            )
            assertTrue(
                AuthRepositoryImpl(
                    FakeAuthApi(),
                    FakeShowTrackApi(),
                    FakeTokenStore(initial = TokenPair("a", "r")),
                    FakePush(),
                ).hasSession(),
            )
        }

    private class FakeAuthApi(
        private val loginFailure: Throwable? = null,
        private val registerFailure: Throwable? = null,
    ) : AuthApi {
        val calls = mutableListOf<String>()

        override suspend fun register(request: RegisterRequest): UserDto {
            calls += "register"
            registerFailure?.let { throw it }
            return UserDto("u-1", request.username, request.email, "2026-09-01T00:00:00Z")
        }

        override suspend fun login(request: LoginRequest): TokenPairDto {
            calls += "login"
            loginFailure?.let { throw it }
            return TokenPairDto("access-1", "refresh-1")
        }

        override suspend fun refresh(request: RefreshRequest) = TokenPairDto("access-2", "refresh-2")

        override suspend fun logout(request: RefreshRequest) = Unit
    }

    /**
     * Hand-written, `GroupRepositoryImplTest.FakeApi`'s own precedent (a fifth copy of this same
     * boilerplate — noted, not fixed, here; this file is not the place to extract a shared one).
     * Only [me] is functional; every other member fails loudly if [AuthRepositoryImpl] ever
     * reaches it, since nothing else on this interface is this class's job.
     */
    @Suppress("TooManyFunctions")
    private class FakeShowTrackApi(
        var meResult: UserDto = UserDto("user-1", "someone", "someone@example.com", "2026-09-01T00:00:00Z"),
        var meFailure: Throwable? = null,
    ) : ShowTrackApi {
        var meCalls = 0
            private set

        override suspend fun me(): UserDto {
            meCalls++
            meFailure?.let { throw it }
            return meResult
        }

        override suspend fun library(
            cursor: String?,
            limit: Int,
            status: String?,
            sort: String?,
            mediaId: String?,
            favorite: Boolean?,
        ): LibraryPageDto = error("not used")

        override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto = error("not used")

        override suspend fun updateLibraryEntry(
            id: String,
            patch: JsonObject,
        ): LibraryEntryDto = error("not used")

        override suspend fun libraryStats(): LibraryStatsDto = error("not used")

        override suspend fun importAniList(request: ImportAniListRequest): ImportSummaryDto = error("not used")

        override suspend fun searchMedia(
            query: String,
            page: Int,
        ): MediaSearchResponseDto = error("not used")

        override suspend fun mediaDetail(id: String): MediaDto = error("not used")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto = error("not used")

        override suspend fun deletePushTarget(id: String): Unit = error("not used")

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto = error("not used")

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto = error("not used")

        override suspend fun groups(): List<GroupDto> = error("not used")

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto = error("not used")

        override suspend fun groupMembers(groupId: String): List<MemberDto> = error("not used")

        override suspend fun rotateGroupInvite(groupId: String): GroupWithInviteDto = error("not used")

        override suspend fun removeGroupMember(
            groupId: String,
            userId: String,
        ): Unit = error("not used")

        override suspend fun groupFeed(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): FeedPageDto = error("not used")

        override suspend fun groupReviews(
            groupId: String,
            mediaId: String,
        ): List<ReviewDto> = error("not used")

        override suspend fun groupWatchlist(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): WatchlistPageDto = error("not used")

        override suspend fun proposeToWatchlist(
            groupId: String,
            request: ProposeTitleRequestDto,
        ): WatchlistItemDto = error("not used")

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ): Unit = error("not used")

        override suspend fun groupProgress(
            groupId: String,
            mediaId: String,
        ): List<ProgressEntryDto> = error("not used")

        override suspend fun createReview(request: CreateReviewRequestDto): ReviewDto = error("not used")

        override suspend fun updateReview(
            id: String,
            patch: JsonObject,
        ): ReviewDto = error("not used")
    }

    private class FakeTokenStore(
        private val initial: TokenPair? = null,
        private val calls: MutableList<String> = mutableListOf(),
    ) : TokenStore {
        var saved: TokenPair? = null
        private var current: TokenPair? = initial

        override suspend fun tokens(): TokenPair? = current

        override suspend fun save(
            access: String,
            refresh: String,
        ) {
            saved = TokenPair(access, refresh)
            current = saved
        }

        override suspend fun clear() {
            calls += "store.clear"
            current = null
        }
    }

    private class FakePush(
        private val failure: Throwable? = null,
        private val calls: MutableList<String> = mutableListOf(),
    ) : PushRepository {
        var loggedIn = false

        override suspend fun register(endpoint: String) = Unit

        override suspend fun unregister() = Unit

        override suspend fun onLoggedIn() {
            failure?.let { throw it }
            loggedIn = true
        }

        override suspend fun onLoggedOut() {
            calls += "push.onLoggedOut"
            failure?.let { throw it }
        }

        override fun decodeMessage(body: ByteArray): PushNotification? = null
    }
}
