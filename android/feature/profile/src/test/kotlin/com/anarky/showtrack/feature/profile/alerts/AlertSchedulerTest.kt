package com.anarky.showtrack.feature.profile.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.anarky.showtrack.feature.profile.FakeAlertSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Against WorkManager's own test driver. Nothing here runs a worker (every alert is still in the
 * future, and the sync needs a network the test scheduler never reports), so this pins only what
 * gets scheduled, replaced and cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeAlertSettingsStore(enabled = true)
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
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        scheduler = AlertScheduler(context, { workManager }, settings)
    }

    @Test
    fun `each planned alert is scheduled as its own tagged work`() =
        runTest {
            val day = alert("a", AlertLead.DAY)
            scheduler.apply(listOf(day, alert("a", AlertLead.SOON)), KEY)

            assertEquals(2, pending(AlertScheduler.ALERT_TAG).size)
            val scheduled = live(day.name).single()
            assertEquals(WorkInfo.State.ENQUEUED, scheduled.state)
            assertEquals(day.delay.toMillis(), scheduled.initialDelayMillis)
        }

    @Test
    fun `scheduling the same alert again replaces it, so a moved air time moves the alert`() =
        runTest {
            val name = AlertRules.name("a", 1, 3, AlertLead.SOON)
            scheduler.apply(listOf(alert("a", AlertLead.SOON, airsIn = Duration.ofHours(10))), KEY)
            val first = live(name).single()

            scheduler.apply(listOf(alert("a", AlertLead.SOON, airsIn = Duration.ofHours(20))), KEY)

            assertNotEquals(first.id, live(name).single().id)
            assertEquals(1, pending(AlertScheduler.ALERT_TAG).size)
        }

    @Test
    fun `alerts for an episode no longer in the plan are cancelled`() =
        runTest {
            scheduler.apply(listOf(alert("a", AlertLead.SOON), alert("b", AlertLead.SOON)), KEY)

            scheduler.apply(listOf(alert("a", AlertLead.SOON)), KEY)

            assertTrue(live(AlertRules.name("b", 1, 3, AlertLead.SOON)).isEmpty())
            assertEquals(1, live(AlertRules.name("a", 1, 3, AlertLead.SOON)).size)
        }

    @Test
    fun `a 24 h alert that is due but has not run yet survives a re-plan`() =
        runTest {
            // Under Doze the overdue 24 h alert and the sync can wait for the same window. A plan
            // made then no longer lists the 24 h alert (its time has passed), but it must still show.
            val day = alert("a", AlertLead.DAY)
            scheduler.apply(listOf(day, alert("a", AlertLead.SOON)), KEY)

            scheduler.apply(listOf(alert("a", AlertLead.SOON, airsIn = Duration.ofHours(23))), KEY)

            assertEquals(1, live(day.name).size)
        }

    @Test
    fun `turning alerts on starts the sync, turning them off cancels everything`() =
        runTest {
            settings.enabled.value = false
            scheduler.setEnabled(true)
            assertEquals(1, live(AlertScheduler.PERIODIC_SYNC).size)
            assertEquals(1, live(AlertScheduler.SYNC_NOW).size)
            scheduler.apply(listOf(alert("a", AlertLead.SOON)), KEY)

            scheduler.setEnabled(false)

            assertTrue(live(AlertScheduler.PERIODIC_SYNC).isEmpty())
            assertTrue(live(AlertScheduler.SYNC_NOW).isEmpty())
            assertTrue(pending(AlertScheduler.ALERT_TAG).isEmpty())
        }

    @Test
    fun `with alerts off a library change schedules nothing`() =
        runTest {
            settings.enabled.value = false

            scheduler.requestSync()

            assertTrue(live(AlertScheduler.SYNC_NOW).isEmpty())
            assertTrue(live(AlertScheduler.PERIODIC_SYNC).isEmpty())
        }

    @Test
    fun `signing out cancels every alert, clears the shade and starts a new account key`() =
        runTest {
            scheduler.requestSync()
            scheduler.apply(listOf(alert("a", AlertLead.SOON)), KEY)
            settings.markFired("old", Instant.now(), Instant.now())
            val keyBefore = settings.alertKey()
            val notifications = context.getSystemService(NotificationManager::class.java)
            EpisodeAlertNotifier.show(context, "media-1", "Severance", "S2 E7 airs soon")

            scheduler.cancelAll()

            assertTrue(pending(AlertScheduler.ALERT_TAG).isEmpty())
            assertTrue(live(AlertScheduler.PERIODIC_SYNC).isEmpty())
            assertTrue(live(AlertScheduler.SYNC_NOW).isEmpty())
            assertTrue(settings.fired.isEmpty())
            assertNotEquals(keyBefore, settings.alertKey())
            assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        }

    private suspend fun pending(tag: String) =
        workManager.getWorkInfosByTagFlow(tag).first().filterNot {
            it.state.isFinished
        }

    private suspend fun live(name: String) =
        workManager.getWorkInfosForUniqueWorkFlow(name).first().filterNot { it.state.isFinished }

    private companion object {
        const val KEY = "key-1"
    }

    private fun alert(
        mediaId: String,
        lead: AlertLead,
        airsIn: Duration = Duration.ofHours(30),
    ) = PlannedAlert(
        name = AlertRules.name(mediaId, 1, 3, lead),
        mediaId = mediaId,
        title = "Title $mediaId",
        season = 1,
        episode = 3,
        airsAt = Instant.now().plus(airsIn),
        lead = lead,
        delay = airsIn - lead.before,
    )
}
