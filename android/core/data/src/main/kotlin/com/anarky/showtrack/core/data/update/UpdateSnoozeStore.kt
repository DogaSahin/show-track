package com.anarky.showtrack.core.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

const val UPDATE_SNOOZE_DATASTORE_NAME = "showtrack_update_snooze"

/** Which release the user postponed, and until when. One release at a time: a newer one replaces it. */
interface UpdateSnoozeStore {
    /** Until when [versionCode] is snoozed, or null when this exact version was never snoozed. */
    suspend fun snoozedUntil(versionCode: Int): Instant?

    suspend fun snooze(
        versionCode: Int,
        until: Instant,
    )
}

private val Context.updateSnoozeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = UPDATE_SNOOZE_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class DataStoreUpdateSnoozeStore(
    private val dataStore: DataStore<Preferences>,
) : UpdateSnoozeStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.updateSnoozeDataStore)

    override suspend fun snoozedUntil(versionCode: Int): Instant? {
        val prefs =
            dataStore.data
                .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
                .first()
        if (prefs[VERSION] != versionCode) return null
        return prefs[UNTIL]?.let(Instant::ofEpochMilli)
    }

    override suspend fun snooze(
        versionCode: Int,
        until: Instant,
    ) {
        dataStore.edit { prefs ->
            prefs[VERSION] = versionCode
            prefs[UNTIL] = until.toEpochMilli()
        }
    }

    private companion object {
        val VERSION = intPreferencesKey("version_code")
        val UNTIL = longPreferencesKey("until_epoch_ms")
    }
}
