package com.anarky.showtrack.core.data.repository

import android.util.Log
import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.model.AuthFailure
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowTrackAuth"
private const val HTTP_UNAUTHORIZED = 401

@Singleton
class AuthRepositoryImpl
    @Inject
    constructor(
        private val api: AuthApi,
        private val tokenStore: TokenStore,
        private val push: PushRepository,
    ) : AuthRepository {
        override suspend fun hasSession(): Boolean = tokenStore.tokens() != null

        @Suppress("TooGenericExceptionCaught")
        override suspend fun login(
            email: String,
            password: String,
        ) {
            try {
                val tokens = api.login(LoginRequest(email = email, password = password))
                tokenStore.save(access = tokens.accessToken, refresh = tokens.refreshToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw mapLoginFailure(failure)
            }
            registerForPush()
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ) {
            try {
                api.register(
                    RegisterRequest(
                        username = username,
                        email = email,
                        password = password,
                        inviteCode = inviteCode,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The account was NOT created — this is a straight AuthFailure, not
                // RegisteredButNotLoggedIn, which is reserved for the account existing already.
                throw mapRegisterFailure(failure)
            }
            try {
                login(email = email, password = password)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The account WAS created; only the follow-up login failed. `failure` is already
                // an AuthFailure here — login() maps its own escapes — so the cause carried below
                // is the domain type, not a raw Retrofit exception.
                throw RegisteredButNotLoggedIn(failure)
            }
        }

        override suspend fun logout() {
            val tokens = tokenStore.tokens()
            // BEFORE the clear: this deletes the server-side push target over an AUTHENTICATED
            // call. Clearing first would 401 and leave the backend pushing to a signed-out device.
            // Routed through detachPush() rather than called bare: store.read()/clearTarget() can
            // still throw even though PushRepositoryImpl swallows its own DELETE failure, and
            // nothing in the PushRepository interface obliges an implementation to swallow
            // anything. An unguarded throw here would skip revoke() and tokenStore.clear() below
            // and leave the user pressing "log out" and staying logged in — worse than login's
            // symmetric case, where a push failure must not be misreported as a login failure.
            detachPush()
            if (tokens != null) {
                revoke(tokens.refresh)
            }
            tokenStore.clear()
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun registerForPush() {
            try {
                push.onLoggedIn()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The credentials were right. Reporting a push failure as a login failure would
                // describe the wrong thing to the one person who cannot act on it.
                Log.w(TAG, "push registration failed after login: ${failure.javaClass.simpleName}")
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun detachPush() {
            try {
                push.onLoggedOut()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A logout must complete locally no matter what the push cleanup does. Failing
                // here would leave the user unable to log out at all when push cleanup fails.
                Log.w(TAG, "push cleanup failed on logout: ${failure.javaClass.simpleName}")
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun revoke(refresh: String) {
            try {
                api.logout(RefreshRequest(refreshToken = refresh))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A logout that cannot reach the server still logs you out locally. The refresh
                // token expires on its own; leaving the user signed in would be worse.
                Log.w(TAG, "could not revoke the refresh token: ${failure.javaClass.simpleName}")
            }
        }

        /** A 401 means the password was wrong; anything else here is not something the user typed. */
        private fun mapLoginFailure(failure: Throwable): AuthFailure =
            when {
                failure is HttpException && failure.code() == HTTP_UNAUTHORIZED ->
                    AuthFailure.InvalidCredentials(failure)
                failure is IOException -> AuthFailure.Offline(failure)
                else -> AuthFailure.Unexpected(failure)
            }

        /** Any HTTP status from register is a refusal — bad invite code, taken email; the status is all there is. */
        private fun mapRegisterFailure(failure: Throwable): AuthFailure =
            when {
                failure is HttpException -> AuthFailure.Refused(failure.code(), failure)
                failure is IOException -> AuthFailure.Offline(failure)
                else -> AuthFailure.Unexpected(failure)
            }
    }
