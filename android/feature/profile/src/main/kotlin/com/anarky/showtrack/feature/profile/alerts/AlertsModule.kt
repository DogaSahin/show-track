package com.anarky.showtrack.feature.profile.alerts

import android.content.Context
import androidx.work.WorkManager
import com.anarky.showtrack.core.data.alerts.EpisodeAlerts
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Gives :core:data its [EpisodeAlerts] (sign-in, sign-out and library changes call it) without
 * that module ever seeing WorkManager: the interface lives there, the implementation here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlertsModule {
    @Binds
    abstract fun episodeAlerts(impl: AlertScheduler): EpisodeAlerts

    @Binds
    abstract fun alertSwitch(impl: AlertScheduler): AlertSwitch

    companion object {
        // Unscoped: WorkManager keeps its own single instance, initialised from ShowTrackApplication.
        @Provides
        fun workManager(
            @ApplicationContext context: Context,
        ): WorkManager = WorkManager.getInstance(context)
    }
}
