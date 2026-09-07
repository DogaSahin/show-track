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

        // ReturnCount: four exits, and each is a distinct "do not retry" decision — a foreign
        // host, the retry bound, the refresh failure, and the success path. TooGenericExceptionCaught /
        // SwallowedException: the catch below is total on purpose; see its comment.
        @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
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

            val fresh =
                runBlocking {
                    mutex.withLock {
                        try {
                            // SINGLE-FLIGHT. Five parallel requests hit one expired token and all
                            // 401 at once. Without this check each would refresh; the backend
                            // stores only the refresh token's HASH and rotates on use, so the
                            // losers invalidate the winner's token and log the user out
                            // mid-session. Re-read inside the lock: if another caller already
                            // refreshed, the stored token differs from the one we sent and we
                            // just use it.
                            //
                            // INSIDE the try, not before it: TokenStore reads the disk, and an
                            // IOException escaping here lands on an OkHttp dispatcher thread,
                            // where OkHttp rethrows it after onFailure and Android kills the
                            // process. A corrupt token file must log the user out, not crash.
                            val current = tokens.tokens() ?: return@withLock null
                            if (current.access != stale) return@withLock current.access

                            val pair = authApi.get().refresh(RefreshRequest(current.refresh))
                            tokens.save(pair.accessToken, pair.refreshToken)
                            pair.accessToken
                        } catch (e: Exception) {
                            // Total on purpose. Every way this can fail — a 401 because the
                            // refresh token was already rotated, an IOException because the
                            // network dropped, a decode failure because the response was not a
                            // token pair — leaves the client with no usable credentials, which is
                            // the same outcome. Narrowing it would let one of them propagate out
                            // of an OkHttp worker thread instead.
                            clearQuietly()
                            events.emit(AuthEvent.LoggedOut)
                            null
                        }
                    }
                } ?: return null

            return response.request
                .newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
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
    }
