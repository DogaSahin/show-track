package com.anarky.showtrack.core.data.update

import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AppReleaseDto
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AppUpdateRepositoryImplTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val t0 = Instant.parse("2026-10-24T12:00:00Z")
    private val clock = MutableClock(t0)
    private val snoozes = FakeSnoozeStore()
    private val api = FakeApi()

    private fun repository(installed: Int) =
        AppUpdateRepositoryImpl(
            api = api.proxy,
            snoozeStore = snoozes,
            clock = clock,
            installedVersionCode = installed,
            updatesDir = File(temp.root, "updates"),
        )

    @Test
    fun `no published release is up to date`() =
        runBlocking {
            api.release = Response.success(204, null as AppReleaseDto?)

            assertEquals(UpdateState.UpToDate, repository(installed = 3).check())
        }

    @Test
    fun `a newer release is available`() =
        runBlocking {
            api.publish(versionCode = 4)

            assertEquals(UpdateState.Available(release(4)), repository(installed = 3).check())
        }

    @Test
    fun `the same version is up to date`() =
        runBlocking {
            api.publish(versionCode = 4)

            assertEquals(UpdateState.UpToDate, repository(installed = 4).check())
        }

    @Test
    fun `below the minimum is required, even when snoozed`() =
        runBlocking {
            api.publish(versionCode = 4, minVersionCode = 3)
            snoozes.snooze(4, t0.plus(Duration.ofHours(10)))

            assertEquals(UpdateState.Required(release(4, min = 3)), repository(installed = 2).check())
        }

    @Test
    fun `a network failure is unknown, never required`() =
        runBlocking {
            api.failure = IOException("offline")
            assertEquals(UpdateState.Unknown, repository(installed = 1).check())

            api.failure = null
            api.release = Response.error(500, "".toResponseBody())
            assertEquals(UpdateState.Unknown, repository(installed = 1).check())

            api.release = Response.error(401, "".toResponseBody())
            assertEquals(UpdateState.Unknown, repository(installed = 1).check())

            api.failure = HttpException(Response.error<Any>(503, "".toResponseBody()))
            assertEquals(UpdateState.Unknown, repository(installed = 1).check())
        }

    @Test
    fun `a snoozed release is hidden until the snooze ends`() =
        runBlocking {
            api.publish(versionCode = 4)
            val repository = repository(installed = 3)

            repository.snooze(release(4))

            clock.now = t0.plus(Duration.ofHours(23))
            assertEquals(UpdateState.UpToDate, repository.check())
            clock.now = t0.plus(Duration.ofHours(25))
            assertEquals(UpdateState.Available(release(4)), repository.check())
        }

    @Test
    fun `a snooze does not hide a newer release`() =
        runBlocking {
            val repository = repository(installed = 3)
            repository.snooze(release(4))
            api.publish(versionCode = 5)

            assertEquals(UpdateState.Available(release(5)), repository.check())
        }

    @Test
    fun `download verifies the hash and returns the file`() =
        runBlocking {
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            api.apk = bytes
            val progress = mutableListOf<Float>()

            val file = repository(installed = 3).download(releaseFor(bytes), progress::add)

            assertEquals("showtrack-4.apk", file.name)
            assertEquals("updates", file.parentFile?.name)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1f, progress.last(), 0f)
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        }

    @Test
    fun `a stale file from an earlier download is cleared first`() =
        runBlocking {
            val stale = File(temp.root, "updates/showtrack-3.apk").apply { parentFile?.mkdirs() }
            stale.writeText("old")
            val bytes = ByteArray(10) { it.toByte() }
            api.apk = bytes

            repository(installed = 3).download(releaseFor(bytes)) {}

            assertFalse(stale.exists())
        }

    @Test
    fun `a download with the wrong hash is rejected and deleted`() =
        runBlocking {
            val bytes = ByteArray(1000) { it.toByte() }
            api.apk = bytes.reversedArray()

            val failure = runCatching { repository(installed = 3).download(releaseFor(bytes)) {} }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("update download failed verification", failure?.message)
            assertTrue(File(temp.root, "updates").listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `a download with the wrong size is rejected and deleted`() =
        runBlocking {
            val bytes = ByteArray(1000) { it.toByte() }
            api.apk = bytes.copyOf(900)

            val failure = runCatching { repository(installed = 3).download(releaseFor(bytes)) {} }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(File(temp.root, "updates").listFiles().orEmpty().isEmpty())
        }

    private fun release(
        code: Int,
        min: Int = 1,
    ) = AppRelease(code, "1.$code", min, "notes", 1000L, "ab".repeat(32))

    private fun releaseFor(bytes: ByteArray) =
        AppRelease(
            versionCode = 4,
            versionName = "1.4",
            minVersionCode = 1,
            notes = "",
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class FakeSnoozeStore : UpdateSnoozeStore {
        private val untilByVersion = mutableMapOf<Int, Instant>()

        override suspend fun snoozedUntil(versionCode: Int): Instant? = untilByVersion[versionCode]

        override suspend fun snooze(
            versionCode: Int,
            until: Instant,
        ) {
            untilByVersion[versionCode] = until
        }
    }

    /**
     * Only two of [ShowTrackApi]'s methods matter here, so a proxy stands in for the hand-written
     * fakes the other repository tests use (those implement every method of the interface).
     */
    private class FakeApi {
        var release: Response<AppReleaseDto>? = null
        var failure: Throwable? = null
        var apk: ByteArray = ByteArray(0)

        fun publish(
            versionCode: Int,
            minVersionCode: Int = 1,
        ) {
            release =
                Response.success(
                    AppReleaseDto(versionCode, "1.$versionCode", minVersionCode, "notes", 1000L, "ab".repeat(32)),
                )
        }

        val proxy: ShowTrackApi =
            Proxy.newProxyInstance(
                ShowTrackApi::class.java.classLoader,
                arrayOf(ShowTrackApi::class.java),
                InvocationHandler { _, method, _ ->
                    try {
                        failure?.let { throw it }
                        when (method.name) {
                            "latestRelease" -> release
                            "downloadRelease" ->
                                apk.toResponseBody(
                                    "application/vnd.android.package-archive".toMediaType(),
                                )
                            else -> error("not used: ${method.name}")
                        }
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                },
            ) as ShowTrackApi
    }
}
