package com.anarky.showtrack.feature.profile.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.anarky.showtrack.feature.profile.FakeAlertSettingsStore
import com.anarky.showtrack.feature.profile.FakeAuthRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** What an alert does when its time comes. Everything it checks is on the phone, so it works offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodeAlertWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeAlertSettingsStore(enabled = true)
    private val auth = FakeAuthRepository()

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `an alert shows once, with the episode and how soon it airs`() =
        runTest {
            run(airsIn = Duration.ofHours(6))
            run(airsIn = Duration.ofHours(6))

            val shown = notifications()
            assertEquals(1, shown.size)
            assertEquals("Severance", shown.single().extras.getString("android.title"))
            assertEquals("S2 E7 airs in 6 hours", shown.single().extras.getString("android.text"))
        }

    @Test
    fun `nothing shows once the episode aired, alerts are off, or the user signed out`() =
        runTest {
            run(airsIn = Duration.ofMinutes(-5))

            settings.enabled.value = false
            run(airsIn = Duration.ofHours(6))

            settings.enabled.value = true
            auth.signedIn = false
            run(airsIn = Duration.ofHours(6))

            assertEquals(0, notifications().size)
        }

    @Test
    fun `nothing shows without notification permission, and the alert is not used up`() =
        runTest {
            shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

            run(airsIn = Duration.ofHours(6))

            assertEquals(0, notifications().size)
            assertEquals(emptySet<String>(), settings.fired)
        }

    private suspend fun run(airsIn: Duration) {
        val alert =
            PlannedAlert(
                name = AlertRules.name("media-1", 2, 7, AlertLead.SOON),
                mediaId = "media-1",
                title = "Severance",
                season = 2,
                episode = 7,
                airsAt = Instant.now().plus(airsIn),
                lead = AlertLead.SOON,
                delay = Duration.ZERO,
            )
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = EpisodeAlertWorker(appContext, workerParameters, settings, auth)
            }
        TestListenableWorkerBuilder<EpisodeAlertWorker>(context)
            .setInputData(alert.toInputData())
            .setWorkerFactory(factory)
            .build()
            .doWork()
    }

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
}
