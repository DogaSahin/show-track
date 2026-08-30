package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
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

    /**
     * `POST /v1/notifications/targets`. Registers this device for push.
     *
     * IDEMPOTENT for `unifiedpush` (backend decision A-O), which is why there is no
     * "have I registered before?" bookkeeping on this side beyond remembering the id to delete:
     * the distributor re-delivers the endpoint through `onNewEndpoint` on every app start, and
     * the server answers 201 the first time and 200 every time after, with the same body. Both
     * are 2xx, so Retrofit returns normally for both and the client does not have to care.
     */
    @POST("v1/notifications/targets")
    suspend fun registerPushTarget(
        @Body request: RegisterTargetRequest,
    ): PushTargetDto

    /**
     * `DELETE /v1/notifications/targets/{id}`, 204 on success.
     *
     * 404 when the id is unknown OR belongs to another account — the backend refuses to
     * distinguish those, so a non-2xx here arrives as an `HttpException` and means only
     * "not deletable by you".
     */
    @DELETE("v1/notifications/targets/{id}")
    suspend fun deletePushTarget(
        @Path("id") id: String,
    )
}
