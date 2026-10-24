package com.anarky.showtrack.feature.profile.alerts

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *   scheduling the same alert again replaces it (a moved air time moves the alert). Alerts are
 *   cancelled per episode and air time, only once that pair is no longer planned: a 24 h alert that
 *   is due but not yet run (Doze) is no longer in a fresh plan, and must still show.
 * - Every alert carries the current account key ([AlertSettingsStore.alertKey]); sign-out replaces
 *   the key, so an alert scheduled before it can never show for whoever signs in next.
 *
 * [WorkManager] is behind [Lazy] so building this (and the Profile screen) never initialises it.
 */
@Singleton
class AlertScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
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

        // Three separate steps, so one failing never skips the others.
        override suspend fun cancelAll() {
            // Off first: alerts are opted into per sign-in, so whoever signs in next chooses.
            safely { settings.setEnabled(false) }
            safely { settings.forgetAccount() }
            safely { cancelScheduled() }
            // Alerts already in the shade are this account's too.
            safely { NotificationManagerCompat.from(context).cancelAll() }
        }

        /**
         * Makes the scheduled alerts match [plan], made for the account [key] belongs to: new and
         * moved ones (re)scheduled, alerts for episodes no longer planned (or planned at another
         * air time) cancelled.
         */
        suspend fun apply(
            plan: List<PlannedAlert>,
            key: String,
        ) {
            val work = workManager.get()
            val wantedEpisodes = plan.mapTo(HashSet()) { it.episodeKey() }
            work
                .getWorkInfosByTagFlow(ALERT_TAG)
                .first()
                .filterNot { it.state.isFinished }
                .filter { info ->
                    info.tags.none {
                        it.startsWith(EPISODE_TAG) &&
                            it.removePrefix(EPISODE_TAG) in wantedEpisodes
                    }
                }.forEach { info -> work.cancelWorkById(info.id) }
            plan.forEach { alert ->
                work.enqueueUniqueWork(
                    alert.name,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<EpisodeAlertWorker>()
                        .setInitialDelay(alert.delay.toMillis(), TimeUnit.MILLISECONDS)
                        .setInputData(alert.toInputData(key))
                        .addTag(ALERT_TAG)
                        .addTag(EPISODE_TAG + alert.episodeKey())
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
            private const val EPISODE_TAG = "episode-alert-episode:"
            private const val SYNC_EVERY_HOURS = 6L

            private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        }
    }

// The episode AND its air time: an alert for an episode whose air time moved is cancelled, while an
// overdue one for an unchanged episode survives.
private fun PlannedAlert.episodeKey() = "$mediaId-${season ?: 0}-$episode@${airsAt.toEpochMilli()}"

internal fun PlannedAlert.toInputData(key: String) =
    workDataOf(
        EpisodeAlertWorker.KEY_ACCOUNT to key,
        EpisodeAlertWorker.KEY_NAME to name,
        EpisodeAlertWorker.KEY_MEDIA_ID to mediaId,
        EpisodeAlertWorker.KEY_TITLE to title,
        EpisodeAlertWorker.KEY_SEASON to (season ?: EpisodeAlertWorker.NO_SEASON),
        EpisodeAlertWorker.KEY_EPISODE to episode,
        EpisodeAlertWorker.KEY_AIRS_AT to airsAt.toEpochMilli(),
    )
