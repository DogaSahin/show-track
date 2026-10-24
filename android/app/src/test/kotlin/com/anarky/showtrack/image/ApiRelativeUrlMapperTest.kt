package com.anarky.showtrack.image

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.request.Options
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only because coil3 Options needs a real Context on Android.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApiRelativeUrlMapperTest {
    private val options = Options(ApplicationProvider.getApplicationContext<Application>())

    private fun map(
        base: String,
        data: String,
    ): String? = ApiRelativeUrlMapper(base.toHttpUrl()).map(data, options)?.toString()

    @Test
    fun `relay path resolves against the host root`() {
        assertEquals(
            "https://h.example/v1/images/tmdb/w500/x.jpg",
            map("https://h.example/", "/v1/images/tmdb/w500/x.jpg"),
        )
    }

    @Test
    fun `relay path keeps a base path prefix`() {
        assertEquals(
            "https://h.example/showtrack/v1/images/tmdb/w500/x.jpg",
            map("https://h.example/showtrack/", "/v1/images/tmdb/w500/x.jpg"),
        )
    }

    @Test
    fun `absolute urls are left to coil`() {
        assertNull(map("https://h.example/", "https://s4.anilist.co/file/x.jpg"))
    }

    @Test
    fun `empty string is left to coil`() {
        assertNull(map("https://h.example/", ""))
    }
}
