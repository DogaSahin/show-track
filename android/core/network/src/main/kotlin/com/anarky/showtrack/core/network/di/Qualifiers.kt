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
