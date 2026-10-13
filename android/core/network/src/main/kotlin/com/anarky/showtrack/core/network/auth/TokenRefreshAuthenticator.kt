package com.anarky.showtrack.core.network.auth

import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Refreshes the access token on a 401 and replays the request — once, and once only, no matter
 * how many requests were in flight (decision A-H).
 *
 * Singleton because the [Mutex] is the whole mechanism: a per-request instance would have a
 * per-request lock, which is no lock at all.
 */
@Singleton
class TokenRefreshAuthenticator
    @Inject
    constructor(
        private val tokens: TokenStore,
        // Provider, not AuthApi: without it Dagger sees OkHttpClient -> Authenticator -> AuthApi
        // -> Retrofit -> OkHttpClient. AuthApi is built on a SEPARATE, unauthenticated client
        // (see NetworkModule and AuthApi's own note), so the cycle is already broken at runtime;
        // the Provider keeps it broken at graph-construction time too, and defers building
        // Retrofit until a token actually expires.
        private val authApi: Provider<AuthApi>,
        private val events: AuthEventBus,
        private val apiHost: ApiHost,
    ) : Authenticator {
        private val mutex = Mutex()

        // ReturnCount: three exits, each a distinct "do not retry" decision — a foreign host, the
        // retry bound, and a refresh that produced no token. The success path is the fourth.
        @Suppress("ReturnCount")
        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            // Never mint a credential for someone else's 401. AuthInterceptor declines to attach
            // a token off-host; without the same check here we would helpfully attach one on the
            // retry instead, which is the leak that check exists to prevent.
            if (!apiHost.owns(response.request.url)) return null

            // BOUNDED. An Authenticator that always returns a request is an infinite retry loop:
            // OkHttp re-issues, gets 401 again, calls us again. priorResponse is the retry chain.
            //
            // Walking the whole chain for a 401, rather than testing priorResponse for non-null:
            // OkHttp also sets priorResponse on a redirect follow-up, and a plain "already
            // retried once" test would then refuse to refresh a FIRST 401 that arrived after a
            // redirect. What must not repeat is an auth retry, so that is what is looked for.
            if (alreadyRetriedAfterUnauthorized(response)) {
                // The replay we authorised came back 401 as well, so the token we just minted is
                // not accepted and these credentials are useless. Giving up quietly would leave
                // every LATER call doing the same dance — refresh (rotating the refresh token),
                // 401, give up — forever, with nothing telling the app to send the user to login.
                runBlocking { mutex.withLock { clearQuietly() } }
                events.emit(AuthEvent.LoggedOut)
                return null
            }

            val stale = response.request.header("Authorization")?.removePrefix("Bearer ")

            val fresh = runBlocking { mutex.withLock { refreshUnderLock(stale) } } ?: return null

            return response.request
                .newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
        }

        /**
         * Three zones, because they fail for different reasons and only some failures mean the
         * credentials are dead:
         * - reading the stored pair: an unreadable store has nothing to refresh with, so sign out;
         * - the refresh call: sign out only when the server rejects the token (see [isRejection]).
         *   A dropped connection, a 5xx during a redeploy or an unreadable body says nothing about
         *   the refresh token, which is still valid: keep it and let this request fail as an
         *   ordinary error;
         * - saving the new pair: a lost compare-and-set means sign-out (or a new login) changed
         *   the store while this refresh was on the wire, so the result is dropped and nothing is
         *   replayed. A save that throws leaves a stale pair on disk the server has already
         *   rotated, so sign out.
         *
         * Every catch is total, CancellationException included: this runs inside runBlocking on
         * an OkHttp worker thread, and anything rethrown from here kills the process.
         *
         * SINGLE-FLIGHT (the reason this is called under the mutex): five parallel requests hit one
         * expired token and all 401 at once. The backend stores only the refresh token's HASH and
         * rotates on use, so without the re-read below the losers would invalidate the winner's
         * token. If another caller already refreshed, the stored token differs from the one we
         * sent and we just use it.
         */
        @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
        private suspend fun refreshUnderLock(stale: String?): String? {
            val current =
                try {
                    tokens.tokens()
                } catch (e: Exception) {
                    signOut()
                    return null
                } ?: return null
            if (current.access != stale) return current.access

            val pair =
                try {
                    authApi.get().refresh(RefreshRequest(current.refresh))
                } catch (e: Exception) {
                    if (isRejection(e)) signOut()
                    return null
                }

            return try {
                val saved =
                    tokens.replace(
                        expectedRefresh = current.refresh,
                        access = pair.accessToken,
                        refresh = pair.refreshToken,
                    )
                if (saved) pair.accessToken else null
            } catch (e: Exception) {
                signOut()
                null
            }
        }

        /** The server looked at the refresh token and refused it. 408 and 429 mean "try later". */
        private fun isRejection(failure: Exception): Boolean =
            failure is HttpException &&
                failure.code() in HTTP_CLIENT_ERRORS &&
                failure.code() != HttpURLConnection.HTTP_CLIENT_TIMEOUT &&
                failure.code() != HTTP_TOO_MANY_REQUESTS

        private suspend fun signOut() {
            clearQuietly()
            events.emit(AuthEvent.LoggedOut)
        }

        /**
         * `clear()` is itself a disk write and can fail for the same reasons the read did. It is
         * only ever called on a path that has already decided the user is logged out, so a
         * failure changes nothing that can still be acted on — and letting it escape would kill
         * the process from an OkHttp worker thread.
         */
        private suspend fun clearQuietly() {
            runCatching { tokens.clear() }
        }

        private fun alreadyRetriedAfterUnauthorized(response: Response): Boolean =
            generateSequence(response.priorResponse) { it.priorResponse }
                .any { it.code == HttpURLConnection.HTTP_UNAUTHORIZED }

        private companion object {
            val HTTP_CLIENT_ERRORS = 400..499
            const val HTTP_TOO_MANY_REQUESTS = 429
        }
    }
