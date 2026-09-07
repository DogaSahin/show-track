package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class AuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * `/v1/auth/logout` answers 204 with no body, and Retrofit short-circuits a 204/205 to a null
     * body without ever consulting the converter — so whether a `suspend fun ...: Unit` survives
     * that is a property of Retrofit's own special-casing, not something the signature makes
     * obvious. It does survive on Retrofit 3.0.0. Pinned here rather than assumed, because the
     * failure would be a crash on the one call the user makes when they log out.
     */
    @Test
    fun `logout tolerates a 204 with no body`() {
        server.enqueue(MockResponse.Builder().code(204).build())

        runBlocking { api.logout(RefreshRequest("r-1")) }

        val recorded = server.takeRequest()
        assertEquals("/v1/auth/logout", recorded.url.encodedPath)
        assertEquals("""{"refresh_token":"r-1"}""", recorded.body?.utf8())
    }
}
