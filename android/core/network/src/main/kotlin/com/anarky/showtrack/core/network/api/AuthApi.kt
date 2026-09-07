package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import com.anarky.showtrack.core.network.dto.TokenPairDto
import com.anarky.showtrack.core.network.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The token endpoints, which are mounted OUTSIDE the backend's authenticated router and must be
 * served by an OkHttp client that has neither the auth interceptor nor the authenticator on it
 * (see NetworkModule). Putting them on the authenticated client deadlocks: a 401 from
 * `/v1/auth/refresh` would re-enter [com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator],
 * which would block on a mutex the outer refresh already holds.
 */
interface AuthApi {
    @POST("v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest,
    ): TokenPairDto

    @POST("v1/auth/refresh")
    suspend fun refresh(
        @Body request: RefreshRequest,
    ): TokenPairDto

    /**
     * 204 No Content: revokes the refresh token server-side. The access token stays valid until
     * it expires. A non-2xx arrives as an `HttpException`, which is what the caller wants here —
     * there is nothing to read from a successful logout. AuthApiTest pins the empty-body case.
     */
    @POST("v1/auth/logout")
    suspend fun logout(
        @Body request: RefreshRequest,
    )

    /**
     * 201 with a [UserDto] — NOT a token pair. The caller must log in afterwards to get tokens
     * (decision C-M). A non-2xx arrives as an `HttpException`, and the status distinguishes the
     * server's two refusals — 400 for an invalid invite code, 409 for a username or email that
     * is already registered.
     */
    @POST("v1/auth/register")
    suspend fun register(
        @Body request: RegisterRequest,
    ): UserDto
}
