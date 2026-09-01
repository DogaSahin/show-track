package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
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
     * the first page. Retrofit omits a null @Query from the string rather than sending "null",
     * which is what makes every filter here optional without a second method.
     *
     * [mediaId] answers "is this title in my library?" in one request (decision C-C); the answer
     * is `items.firstOrNull()`, where null means "not in your library".
     */
    @GET("v1/library")
    suspend fun library(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
        @Query("status") status: String?,
        @Query("sort") sort: String?,
        @Query("media_id") mediaId: String?,
    ): LibraryPageDto

    @POST("v1/library")
    suspend fun addLibraryEntry(
        @Body request: AddLibraryEntryRequest,
    ): LibraryEntryDto

    /**
     * `PATCH /v1/library/{id}`. The body is a [JsonObject] rather than a data class because
     * `score` is a tri-state field: absent means "leave it", `null` means "unrate", and a string
     * means "set it". A nullable Kotlin property collapses the first two. :core:data builds the
     * object, where the caller's intent is known.
     */
    @PATCH("v1/library/{id}")
    suspend fun updateLibraryEntry(
        @Path("id") id: String,
        @Body patch: JsonObject,
    ): LibraryEntryDto

    @GET("v1/media/search")
    suspend fun searchMedia(
        @Query("q") query: String,
        @Query("page") page: Int,
    ): MediaSearchResponseDto

    /** `GET /v1/media/{id}`, a MediaDetail — the same shape [LibraryEntryDto] embeds. */
    @GET("v1/media/{id}")
    suspend fun mediaDetail(
        @Path("id") id: String,
    ): MediaDto

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
