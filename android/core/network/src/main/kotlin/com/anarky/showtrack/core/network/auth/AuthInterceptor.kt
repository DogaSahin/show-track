package com.anarky.showtrack.core.network.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the access token to every request on the authenticated client — to our own host only.
 *
 * `runBlocking` is correct here rather than a smell: OkHttp's interceptor chain is a blocking
 * API called on a worker thread, so there is no coroutine to suspend in. The blocked work is a
 * DataStore read of a file already in the page cache.
 */
class AuthInterceptor
    @Inject
    constructor(
        private val tokens: TokenStore,
        private val apiHost: ApiHost,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // Two ways to end up sending the request untouched, and they are different things:
            // it is not addressed to us, or we have no token yet (a login request, or a first
            // launch). Both proceed; neither is an error.
            val access =
                if (apiHost.owns(request.url)) runBlocking { tokens.tokens()?.access } else null
            return chain.proceed(
                access?.let { request.newBuilder().header("Authorization", "Bearer $it").build() } ?: request,
            )
        }
    }
