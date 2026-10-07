package com.anarky.showtrack.core.data.alerts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val ALERT_SETTINGS_DATASTORE_NAME = "showtrack_episode_alerts"

/**
 * The Episode alerts switch, which alerts already fired, and the current alert key, kept on the
 * phone only (and out of backups).
 *
 * - **Fired** records each alert name with its episode's air time, so a re-planned alert never
 *   shows twice; entries are dropped a day after their episode aired, when nothing can re-plan them.
 * - **The alert key** is stamped into every scheduled alert and replaced by [forgetAccount]. An
 *   alert carrying another key (scheduled before a sign-out, or restored onto another phone) never
 *   shows, so one account's alerts cannot reach the next one, even offline.
 */
interface AlertSettingsStore {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** The key alerts scheduled now belong to. */
    suspend fun alertKey(): String

    /** True when [name] had not fired before, recording it; false when it already had. */
    suspend fun markFired(
        name: String,
        airsAt: Instant,
        now: Instant = Instant.now(),
    ): Boolean

    /** Sign-out: forget which alerts fired and start a new key. */
    suspend fun forgetAccount()
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

    override suspend fun alertKey(): String {
        dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .first()[KEY]
            ?.let { return it }
        var key = ""
        dataStore.edit { prefs -> key = prefs[KEY] ?: UUID.randomUUID().toString().also { prefs[KEY] = it } }
        return key
    }

    override suspend fun markFired(
        name: String,
        airsAt: Instant,
        now: Instant,
    ): Boolean {
        var fresh = false
        dataStore.edit { prefs ->
            val fired = prefs[FIRED].orEmpty()
            if (fired.none { it.substringBeforeLast(SEPARATOR) == name }) {
                fresh = true
                val cutoff = now.minus(KEEP_AFTER_AIRING).toEpochMilli()
                val kept = fired.filter { (it.substringAfterLast(SEPARATOR).toLongOrNull() ?: 0L) >= cutoff }
                prefs[FIRED] = kept.toSet() + "$name$SEPARATOR${airsAt.toEpochMilli()}"
            }
        }
        return fresh
    }

    override suspend fun forgetAccount() {
        dataStore.edit { prefs ->
            prefs.remove(FIRED)
            prefs[KEY] = UUID.randomUUID().toString()
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val FIRED = stringSetPreferencesKey("fired")
        val KEY = stringPreferencesKey("alert_key")
        const val SEPARATOR = "@"
        val KEEP_AFTER_AIRING: Duration = Duration.ofDays(1)
    }
}
