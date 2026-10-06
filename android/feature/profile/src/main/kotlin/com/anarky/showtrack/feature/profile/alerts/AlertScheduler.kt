package com.anarky.showtrack.feature.profile.alerts

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import com.anarky.showtrack.core.data.alerts.EpisodeAlerts
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowTrackAlerts"

/** The Profile switch's view of alerts; a seam so the ViewModel is tested without WorkManager. */
interface AlertSwitch {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

/**
 * Everything WorkManager-shaped about episode alerts.
 *
 * - A periodic [AlertSyncWorker] (every 6 h, network required) and a one-off run of it after a
 *   library change or sign-in re-plan alerts from the server's air dates.
 * - Each alert is its own unique [EpisodeAlertWorker] request, named by [AlertRules.name], so
 *   scheduling the same alert again replaces it (a moved air time moves the alert) and an alert
 *   that is no longer planned is cancelled by name.
 *
 * [WorkManager] is behind [Lazy] so building this (and the Profile screen) never initialises it.
 */
@Singleton
class AlertScheduler
    @Inject
    constructor(
        private val workManager: Lazy<WorkManager>,
        private val settings: AlertSettingsStore,
    ) : EpisodeAlerts,
        AlertSwitch {
        override val enabled: Flow<Boolean> = settings.enabled

        override suspend fun setEnabled(enabled: Boolean) {
            settings.setEnabled(enabled)
            if (enabled) requestSync() else safely { cancelScheduled() }
        }

        override suspend fun requestSync() =
            safely {
                if (!settings.enabled.first()) return@safely
                val work = workManager.get()
                work.enqueueUniquePeriodicWork(
                    PERIODIC_SYNC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AlertSyncWorker>(SYNC_EVERY_HOURS, TimeUnit.HOURS)
                        .setConstraints(online)
                        .build(),
                )
                work.enqueueUniqueWork(
                    SYNC_NOW,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AlertSyncWorker>().setConstraints(online).build(),
                )
            }

        override suspend fun cancelAll() =
            safely {
                cancelScheduled()
                settings.clearFired()
            }

        /** Makes the scheduled alerts match [plan]: new and moved ones (re)scheduled, the rest cancelled. */
        suspend fun apply(plan: List<PlannedAlert>) {
            val work = workManager.get()
            val wanted = plan.mapTo(HashSet()) { it.name }
            work
                .getWorkInfosByTagFlow(ALERT_TAG)
                .first()
                .filterNot { it.state.isFinished }
                .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG) }
                .filterNot { it in wanted }
                .forEach(work::cancelUniqueWork)
            plan.forEach { alert ->
                work.enqueueUniqueWork(
                    alert.name,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<EpisodeAlertWorker>()
                        .setInitialDelay(alert.delay.toMillis(), TimeUnit.MILLISECONDS)
                        .setInputData(alert.toInputData())
                        .addTag(ALERT_TAG)
                        .addTag(NAME_TAG + alert.name)
                        .build(),
                )
            }
        }

        private fun cancelScheduled() {
            val work = workManager.get()
            work.cancelUniqueWork(PERIODIC_SYNC)
            work.cancelUniqueWork(SYNC_NOW)
            work.cancelAllWorkByTag(ALERT_TAG)
        }

        // An alert is a convenience: failing to schedule one must never fail what the user did.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun safely(block: suspend () -> Unit) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "alert scheduling failed: ${failure.javaClass.simpleName}")
            }
        }

        companion object {
            const val ALERT_TAG = "episode-alert"
            const val PERIODIC_SYNC = "episode-alert-sync"
            const val SYNC_NOW = "episode-alert-sync-now"
            private const val NAME_TAG = "episode-alert-name:"
            private const val SYNC_EVERY_HOURS = 6L

            private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        }
    }

internal fun PlannedAlert.toInputData() =
    workDataOf(
        EpisodeAlertWorker.KEY_NAME to name,
        EpisodeAlertWorker.KEY_MEDIA_ID to mediaId,
        EpisodeAlertWorker.KEY_TITLE to title,
        EpisodeAlertWorker.KEY_SEASON to (season ?: EpisodeAlertWorker.NO_SEASON),
        EpisodeAlertWorker.KEY_EPISODE to episode,
        EpisodeAlertWorker.KEY_AIRS_AT to airsAt.toEpochMilli(),
    )
