package com.anarky.showtrack.core.network.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the access token to every request on the authenticated client.
 *
 * `runBlocking` is correct here rather than a smell: OkHttp's interceptor chain is a blocking
 * API called on a worker thread, so there is no coroutine to suspend in. The blocked work is a
 * DataStore read of a file already in the page cache.
 */
class AuthInterceptor
    @Inject
    constructor(
        private val tokens: TokenStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val access =
                runBlocking { tokens.tokens()?.access }
                    ?: return chain.proceed(chain.request())
            return chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .header("Authorization", "Bearer $access")
                    .build(),
            )
        }
    }
