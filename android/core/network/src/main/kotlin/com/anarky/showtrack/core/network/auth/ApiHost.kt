package com.anarky.showtrack.core.network.auth

import com.anarky.showtrack.core.network.di.BaseUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is this one of ours?" — asked before any credential is attached to a request.
 *
 * The authenticated client is a general-purpose [okhttp3.OkHttpClient]: anything handed it gets
 * the auth interceptor and the authenticator. Sharing it with an image loader is a reasonable
 * thing for a later task to want, and poster URLs point at third-party CDNs — so without this
 * check a shared client would ship the user's access token to every host it fetched an image
 * from. A KDoc warning is a hope; this is a guard.
 */
@Singleton
class ApiHost
    @Inject
    constructor(
        @param:BaseUrl baseUrl: String,
    ) {
        private val host = baseUrl.toHttpUrl().host

        fun owns(url: HttpUrl): Boolean = url.host.equals(host, ignoreCase = true)
    }
