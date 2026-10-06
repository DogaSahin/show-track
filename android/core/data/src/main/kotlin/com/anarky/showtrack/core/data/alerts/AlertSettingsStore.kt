package com.anarky.showtrack.core.data.alerts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

const val ALERT_SETTINGS_DATASTORE_NAME = "showtrack_episode_alerts"

/**
 * The Episode alerts switch, and which alerts already fired, kept on the phone only.
 *
 * "Fired" is a set of alert names (title, episode, threshold), so a rescheduled or re-planned alert
 * never shows twice. Bounded: past [FIRED_LIMIT] names about half are dropped (a DataStore set keeps
 * no order). At two alerts an episode that limit is far beyond what a library ever has scheduled at
 * once, so in practice only names for long-aired episodes go.
 */
interface AlertSettingsStore {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** True when [name] had not fired before, recording it; false when it already had. */
    suspend fun markFired(name: String): Boolean

    suspend fun clearFired()

    companion object {
        const val FIRED_LIMIT = 400
    }
}

// One DataStore per file per process, so a top-level delegate, as the other stores do.
private val Context.alertSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ALERT_SETTINGS_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class DataStoreAlertSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : AlertSettingsStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.alertSettingsDataStore)

    // Off until the user turns it on; an unreadable file reads as off, never as a crash.
    override val enabled: Flow<Boolean> =
        dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> prefs[ENABLED] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[ENABLED] = enabled }
    }

    override suspend fun markFired(name: String): Boolean {
        var fresh = false
        dataStore.edit { prefs ->
            val fired = prefs[FIRED].orEmpty()
            if (name !in fired) {
                fresh = true
                val kept =
                    if (fired.size >=
                        AlertSettingsStore.FIRED_LIMIT
                    ) {
                        fired.drop(fired.size / 2).toSet()
                    } else {
                        fired
                    }
                prefs[FIRED] = kept + name
            }
        }
        return fresh
    }

    override suspend fun clearFired() {
        dataStore.edit { prefs -> prefs.remove(FIRED) }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val FIRED = stringSetPreferencesKey("fired")
    }
}
