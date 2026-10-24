package com.anarky.showtrack.core.data.update

import android.content.Context
import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState
import com.anarky.showtrack.core.network.api.ShowTrackApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * [updatesDir] is where downloads land (`cacheDir/updates` in production); the injected
 * constructor derives it from the app context so tests can hand in a temporary folder instead.
 */
@Singleton
class AppUpdateRepositoryImpl(
    private val api: ShowTrackApi,
    private val snoozeStore: UpdateSnoozeStore,
    private val clock: Clock,
    private val installedVersionCode: Int,
    private val updatesDir: File,
) : AppUpdateRepository {
    @Inject
    constructor(
        api: ShowTrackApi,
        snoozeStore: UpdateSnoozeStore,
        clock: Clock,
        @InstalledVersionCode installedVersionCode: Int,
        @ApplicationContext context: Context,
    ) : this(api, snoozeStore, clock, installedVersionCode, File(context.cacheDir, UPDATES_DIR))

    @Suppress("TooGenericExceptionCaught") // Any failure to ask is "unknown", by contract.
    override suspend fun check(): UpdateState =
        try {
            val response = api.latestRelease()
            val dto = response.body()
            when {
                !response.isSuccessful -> UpdateState.Unknown
                response.code() == HTTP_NO_CONTENT || dto == null -> UpdateState.UpToDate
                else -> classify(dto.toDomain())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UpdateState.Unknown
        }

    private suspend fun classify(release: AppRelease): UpdateState =
        when {
            installedVersionCode < release.minVersionCode -> UpdateState.Required(release)
            installedVersionCode >= release.versionCode -> UpdateState.UpToDate
            isSnoozed(release) -> UpdateState.UpToDate
            else -> UpdateState.Available(release)
        }

    private suspend fun isSnoozed(release: AppRelease): Boolean =
        snoozeStore.snoozedUntil(release.versionCode)?.isAfter(clock.instant()) == true

    override suspend fun snooze(release: AppRelease) {
        snoozeStore.snooze(release.versionCode, clock.instant().plus(SNOOZE))
    }

    @Suppress("TooGenericExceptionCaught") // Clean up the partial file on any failure, then rethrow.
    override suspend fun download(
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            updatesDir.deleteRecursively()
            if (!updatesDir.mkdirs()) throw IOException("cannot create the update directory")
            val target = File(updatesDir, "showtrack-${release.versionCode}.apk")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val total = copy(target, digest, release.sizeBytes, onProgress)
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != release.sizeBytes || actualHash != release.sha256.lowercase()) {
                    throw IOException("update download failed verification")
                }
                onProgress(1f)
                target
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }

    private suspend fun copy(
        target: File,
        digest: MessageDigest,
        expectedBytes: Long,
        onProgress: (Float) -> Unit,
    ): Long =
        api.downloadRelease().use { body ->
            body.byteStream().use { input ->
                target.outputStream().use { output -> pump(input, output, digest, expectedBytes, onProgress) }
            }
        }

    private suspend fun pump(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        expectedBytes: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) return total
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            if (expectedBytes > 0) onProgress((total.toFloat() / expectedBytes).coerceAtMost(1f))
        }
    }

    private companion object {
        const val UPDATES_DIR = "updates"
        const val HTTP_NO_CONTENT = 204
        const val BUFFER_BYTES = 16 * 1024
        val SNOOZE: Duration = Duration.ofHours(24)
    }
}
