package com.anarky.showtrack.core.network.di

import javax.inject.Qualifier

/**
 * The OkHttp client carrying the auth interceptor and the token-refresh authenticator. Everything
 * except the token endpoints goes through it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

/**
 * The bare OkHttp client. [com.anarky.showtrack.core.network.api.AuthApi] is built on this one:
 * a 401 from `/v1/auth/refresh` served by the authenticated client would re-enter the
 * authenticator on another thread and deadlock on the mutex the outer refresh still holds.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

/**
 * The API's base URL. A binding rather than a direct `BuildConfig` read inside the providers, so
 * the graph can be stood up against a test server — a provider that reaches for a compile-time
 * constant is a provider no test can point anywhere else.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrl
