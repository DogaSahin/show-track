package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.LibraryPageDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The authenticated surface of the backend. Served by the OkHttp client that carries
 * [com.anarky.showtrack.core.network.auth.AuthInterceptor] and
 * [com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator].
 */
interface ShowTrackApi {
    /**
     * `GET /v1/library`. Cursor-paginated: pass the previous page's `next_cursor`, or null for
     * the first page. A null cursor is omitted from the query string by Retrofit rather than
     * sent as `cursor=null`.
     */
    @GET("v1/library")
    suspend fun library(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): LibraryPageDto
}
