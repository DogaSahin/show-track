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
        @BaseUrl baseUrl: String,
    ) {
        private val origin = baseUrl.toHttpUrl()

        /**
         * The whole ORIGIN — scheme, host and port — not the host alone.
         *
         * This is the check that decides whether the Bearer token goes out, so every part of the
         * origin has to be pinned or the guard admits something it was written to refuse. A
         * host-only comparison sent the token to `http://<api-host>/...` when the API is https
         * (a downgrade that puts the credential on the wire in clear, and one an attacker on the
         * network can force by answering the plaintext request), and to `https://<api-host>:8443/`
         * — a different service on the same machine is a different trust boundary, which is
         * exactly the reasoning the backend's own endpoint check uses when it compares netloc
         * rather than hostname.
         *
         * `HttpUrl.port` is the explicit port or the scheme's default, so `https://h/` and
         * `https://h:443/` compare equal without a special case. Host is compared case-insensitively
         * for belt and braces; OkHttp has already canonicalised it.
         */
        fun owns(url: HttpUrl): Boolean =
            url.scheme == origin.scheme &&
                url.port == origin.port &&
                url.host.equals(origin.host, ignoreCase = true)
    }
