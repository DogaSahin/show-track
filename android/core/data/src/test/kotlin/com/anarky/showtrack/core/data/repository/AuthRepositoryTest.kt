package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.alerts.EpisodeAlerts
import com.anarky.showtrack.core.data.session.UserData
import com.anarky.showtrack.core.data.session.UserDataCleaner
import com.anarky.showtrack.core.model.AuthFailure
import com.anarky.showtrack.core.model.CurrentUser
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.auth.TokenPair
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.EpisodeListDto
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
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import com.anarky.showtrack.core.network.dto.ResolveMediaRequestDto
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.SetWatchedRequestDto
import com.anarky.showtrack.core.network.dto.TokenPairDto
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchedEpisodesDto
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
import java.time.Instant

/** Builds an `HttpException` the way Retrofit itself does, for a non-2xx response. */
private fun httpError(code: Int): HttpException = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

/**
 * Robolectric because a caught revoke failure is logged through `android.util.Log`, which a
 * plain JVM test answers with "not mocked" — and THROWS, which would fail the very tests that
 * check a failure is swallowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthRepositoryTest {
    @Test
    fun `register creates the account and then logs in`() =
        runTest {
            val api = FakeAuthApi()
            val store = FakeTokenStore()
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), store, FakeAlerts(), userData)

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
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), store, FakeAlerts(), userData)

            val failure =
                runCatching {
                    repository.register("someone", "a@example.com", "hunter2hunter2", "CODE")
                }.exceptionOrNull()

            assertTrue(failure is RegisteredButNotLoggedIn)
            assertNull(store.saved)
        }

    @Test
    fun `login clears earlier alerts, then plans this account's`() =
        runTest {
            val calls = mutableListOf<String>()
            val alerts = FakeAlerts(calls = calls)
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), FakeTokenStore(), alerts, userData)

            repository.login("a@example.com", "hunter2hunter2")

            assertEquals(listOf("alerts.cancelAll"), calls)
            assertEquals(1, alerts.syncRequests)
        }

    @Test
    fun `logout cancels episode alerts before it clears the tokens`() =
        runTest {
            // Nothing scheduled for this account may fire once it is signed out. Order, not just
            // outcome.
            // ONE shared recorder, not one list per fake: concatenating two separate lists
            // yields their declaration order, not the call order, and would pass or fail
            // regardless of what the code does.
            val calls = mutableListOf<String>()
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"), calls = calls)
            val alerts = FakeAlerts(calls = calls)
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, alerts, userData)

            repository.logout()

            assertEquals(listOf("alerts.cancelAll", "store.clear"), calls)
            assertFalse(repository.hasSession())
        }

    @Test
    fun `a wrong password surfaces as invalid credentials`() =
        runTest {
            val api = FakeAuthApi(loginFailure = httpError(401))
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakeAlerts(), userData)

            val failure = runCatching { repository.login("a@example.com", "wrong") }.exceptionOrNull()

            assertTrue(failure is AuthFailure.InvalidCredentials)
        }

    @Test
    fun `being offline during login surfaces as being offline`() =
        runTest {
            val api = FakeAuthApi(loginFailure = IOException("offline"))
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakeAlerts(), userData)

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
            val repository = AuthRepositoryImpl(api, FakeShowTrackApi(), FakeTokenStore(), FakeAlerts(), userData)

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
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakeAlerts(), userData)

            val id = repository.currentUserId()

            assertEquals("user-42", id)
        }

    @Test
    fun `currentUser maps the whole account and refreshes the cached id`() =
        runTest {
            val showTrackApi =
                FakeShowTrackApi(meResult = UserDto("user-42", "alex", "a@b.test", "2025-03-14T10:00:00Z"))
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakeAlerts(), userData)

            val user = repository.currentUser()
            repository.currentUserId()

            assertEquals(
                CurrentUser("user-42", "alex", "a@b.test", Instant.parse("2025-03-14T10:00:00Z")),
                user,
            )
            assertEquals("currentUserId reuses what currentUser fetched", 1, showTrackApi.meCalls)
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
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakeAlerts(), userData)

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
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, store, FakeAlerts(), userData)
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
            val repository = AuthRepositoryImpl(FakeAuthApi(), showTrackApi, store, FakeAlerts(), userData)
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
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakeAlerts(), userData)

            val failure = runCatching { repository.currentUserId() }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Offline)
        }

    /** The negative control: an unmapped failure (a 500, a malformed response) is Unexpected, not Offline. */
    @Test
    fun `an unmapped failure while resolving currentUserId surfaces as Unexpected`() =
        runTest {
            val showTrackApi = FakeShowTrackApi(meFailure = httpError(500))
            val repository =
                AuthRepositoryImpl(FakeAuthApi(), showTrackApi, FakeTokenStore(), FakeAlerts(), userData)

            val failure = runCatching { repository.currentUserId() }.exceptionOrNull()

            assertTrue(failure is AuthFailure.Unexpected)
        }

    @Test
    fun `hasSession is false with nothing stored and true with tokens`() =
        runTest {
            assertFalse(
                AuthRepositoryImpl(
                    FakeAuthApi(),
                    FakeShowTrackApi(),
                    FakeTokenStore(),
                    FakeAlerts(),
                    userData,
                ).hasSession(),
            )
            assertTrue(
                AuthRepositoryImpl(
                    FakeAuthApi(),
                    FakeShowTrackApi(),
                    FakeTokenStore(initial = TokenPair("a", "r")),
                    FakeAlerts(),
                    userData,
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
            type: String?,
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

        override suspend fun resolveMedia(request: ResolveMediaRequestDto): MediaDto = error("not used")

        override suspend fun mediaEpisodes(id: String): EpisodeListDto = error("not used")

        override suspend fun deleteLibraryEntry(id: String): Unit = error("not used here")

        override suspend fun watchedEpisodes(id: String): WatchedEpisodesDto = error("not used")

        override suspend fun setWatchedEpisodes(
            id: String,
            request: SetWatchedRequestDto,
        ): LibraryEntryDto = error("not used")

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto = error("not used")

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto = error("not used")

        override suspend fun groups(): List<GroupDto> = error("not used")

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto = error("not used")

        override suspend fun groupMembers(groupId: String): List<MemberDto> = error("not used")

        override suspend fun groupInvite(groupId: String): GroupWithInviteDto = error("not used")

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

    private class FakeAlerts(
        private val calls: MutableList<String> = mutableListOf(),
    ) : EpisodeAlerts {
        var syncRequests = 0

        override suspend fun requestSync() {
            syncRequests++
        }

        override suspend fun cancelAll() {
            calls += "alerts.cancelAll"
        }
    }

    private val cleared = mutableListOf<TokenPair?>()
    private var clearStore: FakeTokenStore? = null

    // Records, at each clear, whether tokens were still stored: the order is the point.
    private val userData =
        UserDataCleaner(
            setOf(
                object : UserData {
                    override suspend fun clearUserData() {
                        cleared += clearStore?.tokens()
                    }
                },
            ),
        )

    @Test
    fun `logout clears this account's data only after its tokens are gone`() =
        runTest {
            val store = FakeTokenStore(initial = TokenPair("access-1", "refresh-1"))
            clearStore = store
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, FakeAlerts(), userData)

            repository.logout()

            assertEquals(listOf<TokenPair?>(null), cleared)
        }

    @Test
    fun `a login clears what an interrupted sign-out left, before the new tokens exist`() =
        runTest {
            val store = FakeTokenStore()
            clearStore = store
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeShowTrackApi(), store, FakeAlerts(), userData)

            repository.login(email = "a@example.com", password = "pw")

            assertEquals(listOf<TokenPair?>(null), cleared)
            assertEquals(TokenPair("access-1", "refresh-1"), store.saved)
        }
}
