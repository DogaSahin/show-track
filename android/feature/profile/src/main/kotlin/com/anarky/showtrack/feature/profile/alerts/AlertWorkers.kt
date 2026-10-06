package com.anarky.showtrack.feature.profile.alerts

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant

private const val TAG = "ShowTrackAlerts"
private const val SYNC_ATTEMPTS = 3

/** Re-plans every alert from the Watching titles' next air dates. */
@HiltWorker
class AlertSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settings: AlertSettingsStore,
        private val auth: AuthRepository,
        private val library: LibraryRepository,
        private val scheduler: AlertScheduler,
    ) : CoroutineWorker(context, params) {
        @Suppress("TooGenericExceptionCaught", "ReturnCount")
        override suspend fun doWork(): Result {
            if (!settings.enabled.first() || !auth.hasSession()) return Result.success()
            val watching =
                try {
                    library.allWatching()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.w(TAG, "alert sync failed: ${failure.javaClass.simpleName}")
                    // A few retries for a flaky connection; after that the next 6-hourly run tries again.
                    return if (runAttemptCount < SYNC_ATTEMPTS) Result.retry() else Result.success()
                }
            // Checked again: alerts may have been turned off, or the user signed out, mid-fetch.
            if (!settings.enabled.first() || !auth.hasSession()) return Result.success()
            scheduler.apply(AlertRules.plan(watching, Instant.now()))
            return Result.success()
        }
    }

/**
 * Shows one alert. Everything it needs travels in its input data, so it works offline. It shows
 * nothing when alerts are off, the user signed out, the episode already aired (the phone was off
 * at the time), or this alert already showed once.
 */
@HiltWorker
class EpisodeAlertWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settings: AlertSettingsStore,
        private val auth: AuthRepository,
    ) : CoroutineWorker(context, params) {
        // Guard clauses: each early return is one reason not to show this alert.
        @Suppress("ReturnCount")
        override suspend fun doWork(): Result {
            val input = inputData
            val name = input.getString(KEY_NAME) ?: return Result.success()
            val mediaId = input.getString(KEY_MEDIA_ID) ?: return Result.success()
            val title = input.getString(KEY_TITLE) ?: return Result.success()
            val airsAt = Instant.ofEpochMilli(input.getLong(KEY_AIRS_AT, 0L))
            val now = Instant.now()
            if (!airsAt.isAfter(now)) return Result.success()
            if (!settings.enabled.first() || !auth.hasSession()) return Result.success()
            if (!EpisodeAlertNotifier.canNotify(applicationContext)) return Result.success()
            if (!settings.markFired(name)) return Result.success()

            val season = input.getInt(KEY_SEASON, NO_SEASON).takeIf { it != NO_SEASON }
            val episode = input.getInt(KEY_EPISODE, 0)
            EpisodeAlertNotifier.show(
                context = applicationContext,
                mediaId = mediaId,
                title = title,
                text = AlertText.body(applicationContext.resources, season, episode, airsAt, now),
            )
            return Result.success()
        }

        companion object {
            const val KEY_NAME = "name"
            const val KEY_MEDIA_ID = "media_id"
            const val KEY_TITLE = "title"
            const val KEY_SEASON = "season"
            const val KEY_EPISODE = "episode"
            const val KEY_AIRS_AT = "airs_at"
            const val NO_SEASON = -1
        }
    }
