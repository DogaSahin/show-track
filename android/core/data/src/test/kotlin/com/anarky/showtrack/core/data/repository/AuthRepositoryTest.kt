package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.model.PushNotification
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.auth.TokenPair
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import com.anarky.showtrack.core.network.dto.TokenPairDto
import com.anarky.showtrack.core.network.dto.UserDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

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
            val repository = AuthRepositoryImpl(api, store, FakePush())

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
            val repository = AuthRepositoryImpl(api, store, FakePush())

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
            val repository = AuthRepositoryImpl(FakeAuthApi(), FakeTokenStore(), push)

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
                AuthRepositoryImpl(FakeAuthApi(), store, FakePush(failure = IOException("offline")))

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
            val repository = AuthRepositoryImpl(FakeAuthApi(), store, push)

            repository.logout()

            assertEquals(listOf("push.onLoggedOut", "store.clear"), calls)
            assertFalse(repository.hasSession())
        }

    @Test
    fun `hasSession is false with nothing stored and true with tokens`() =
        runTest {
            assertFalse(AuthRepositoryImpl(FakeAuthApi(), FakeTokenStore(), FakePush()).hasSession())
            assertTrue(
                AuthRepositoryImpl(
                    FakeAuthApi(),
                    FakeTokenStore(initial = TokenPair("a", "r")),
                    FakePush(),
                ).hasSession(),
            )
        }

    private class FakeAuthApi(
        private val loginFailure: Throwable? = null,
    ) : AuthApi {
        val calls = mutableListOf<String>()

        override suspend fun register(request: RegisterRequest): UserDto {
            calls += "register"
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
        }

        override fun decodeMessage(body: ByteArray): PushNotification? = null
    }
}
