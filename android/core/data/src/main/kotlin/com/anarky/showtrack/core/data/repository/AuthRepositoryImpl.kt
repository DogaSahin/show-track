package com.anarky.showtrack.core.data.repository

import android.util.Log
import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowTrackAuth"

@Singleton
class AuthRepositoryImpl
    @Inject
    constructor(
        private val api: AuthApi,
        private val tokenStore: TokenStore,
        private val push: PushRepository,
    ) : AuthRepository {
        override suspend fun hasSession(): Boolean = tokenStore.tokens() != null

        override suspend fun login(
            email: String,
            password: String,
        ) {
            val tokens = api.login(LoginRequest(email = email, password = password))
            tokenStore.save(access = tokens.accessToken, refresh = tokens.refreshToken)
            registerForPush()
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ) {
            api.register(
                RegisterRequest(
                    username = username,
                    email = email,
                    password = password,
                    inviteCode = inviteCode,
                ),
            )
            try {
                login(email = email, password = password)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw RegisteredButNotLoggedIn(failure)
            }
        }

        override suspend fun logout() {
            val tokens = tokenStore.tokens()
            // BEFORE the clear: this deletes the server-side push target over an AUTHENTICATED
            // call. Clearing first would 401 and leave the backend pushing to a signed-out device.
            push.onLoggedOut()
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
    }
