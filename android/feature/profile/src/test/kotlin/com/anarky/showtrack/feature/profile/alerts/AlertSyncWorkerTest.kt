package com.anarky.showtrack.feature.profile.alerts

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.feature.profile.FakeAlertSettingsStore
import com.anarky.showtrack.feature.profile.FakeAuthRepository
import com.anarky.showtrack.feature.profile.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** The re-plan run: Watching titles in, scheduled alerts out, and the guards around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertSyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeAlertSettingsStore(enabled = true)
    private val auth = FakeAuthRepository()
    private val library = FakeLibraryRepository()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlertScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.DEBUG)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = AlertScheduler(context, { workManager }, settings)
        library.watching = listOf(watching(airsIn = Duration.ofHours(30)))
    }

    @Test
    fun `the Watching titles' next episodes are scheduled`() =
        runTest {
            assertEquals(ListenableWorker.Result.success(), run())

            assertEquals(2, scheduled().size)
        }

    @Test
    fun `nothing is scheduled with alerts off, signed out, or signed out mid-fetch`() =
        runTest {
            settings.enabled.value = false
            run()
            settings.enabled.value = true
            auth.signedIn = false
            run()
            auth.signedIn = true
            library.duringAllWatching = { auth.signedIn = false }
            run()

            assertTrue(scheduled().isEmpty())
        }

    @Test
    fun `a failed fetch is retried a few times, then left to the next run`() =
        runTest {
            library.allWatchingFailure = IOException("offline")

            assertEquals(ListenableWorker.Result.retry(), run(attempt = 0))
            assertEquals(ListenableWorker.Result.success(), run(attempt = 3))
            assertTrue(scheduled().isEmpty())
        }

    private suspend fun scheduled() =
        workManager.getWorkInfosByTagFlow(AlertScheduler.ALERT_TAG).first().filterNot { it.state.isFinished }

    private suspend fun run(attempt: Int = 0): ListenableWorker.Result {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = AlertSyncWorker(appContext, workerParameters, settings, auth, library, scheduler)
            }
        return TestListenableWorkerBuilder<AlertSyncWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(factory)
            .build()
            .doWork()
    }

    private fun watching(airsIn: Duration) =
        LibraryEntry(
            id = "entry-1",
            status = UserMediaStatus.WATCHING,
            score = null,
            progress = 6,
            favorite = false,
            updatedAt = Instant.now(),
            media =
                Media(
                    id = "media-1",
                    source = MediaSource.TMDB,
                    externalId = "95396",
                    type = MediaType.TV,
                    title = "Severance",
                    year = 2022,
                    genres = emptyList(),
                    coverImageUrl = null,
                    status = MediaStatus.AIRING,
                    nextEpisodeSeason = 2,
                    nextEpisodeNumber = 7,
                    nextEpisodeDate = Instant.now().plus(airsIn),
                    daysUntilNextEpisode = null,
                ),
        )
}
