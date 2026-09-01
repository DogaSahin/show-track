package com.anarky.showtrack.core.data.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DataStore file. Separate from the token store's: different lifetime, different secrecy.
 *
 * PUBLIC rather than internal, for the one reason `TOKEN_DATASTORE_NAME` is: `:app`'s
 * `TokenBackupExclusionTest` reads `res/xml/backup_rules.xml` and
 * `res/xml/data_extraction_rules.xml` and asserts they exclude
 * `datastore/$PUSH_DATASTORE_NAME.preferences_pb`. The exclusion is a path string in XML and the
 * name is a constant in this module; nothing but that test connects the two, so renaming this
 * would otherwise leave the exclusion matching nothing, silently.
 */
const val PUSH_DATASTORE_NAME = "showtrack_push"

/**
 * What this device registered, and the ONE thing it cannot re-derive.
 *
 * `DELETE /v1/notifications/targets/{id}` takes an id, and the list endpoint deliberately
 * withholds `target` — the endpoint is a bearer secret — so there is no way to look a row up by
 * the endpoint we know. `onUnregistered` can therefore only clean up after itself if the id was
 * written down at registration time. Without it the row survives, and the backend keeps POSTing
 * to an endpoint nothing answers until five failed attempts turn into a 404 and prune it.
 *
 * The endpoint is stored alongside so [PushRepositoryImpl] can skip a redundant POST when nothing
 * changed. That is an optimisation, NOT the idempotency guarantee — that lives on the server,
 * where a cleared app data directory cannot defeat it (decision A-O).
 */
data class PushRegistration(
    val targetId: String,
    val endpoint: String,
)

/**
 * An interface over one file, for the reason `TokenStore` is one in `:core:network`: the
 * DataStore delegate below is a top-level `Context` extension, so a test that wanted to observe
 * [PushRepositoryImpl]'s ordering around it could not substitute one. What is under test there is
 * "write only after the POST succeeded, clear only after the DELETE did", which is this module's
 * behaviour — DataStore's persistence is not.
 */
interface PushRegistrationStore {
    suspend fun read(): PushRegistration?

    suspend fun write(registration: PushRegistration)

    suspend fun clear()

    /**
     * The endpoint alone, whether or not a target id is stored. The endpoint belongs to this
     * DEVICE and its distributor; the target id belongs to the ACCOUNT. Phase 8 stored and
     * cleared them together, which is why a logout used to lose the endpoint (decision C-P).
     */
    suspend fun readEndpoint(): String?

    /** Forgets the account-scoped target id and keeps the device-scoped endpoint. */
    suspend fun clearTarget()
}

// A top-level delegate, which is how DataStore enforces one instance per file per process —
// constructing two over the same file throws. The corruption handler covers WRITES as well as
// reads: without it a damaged file would make `clear()` throw too, leaving no in-app recovery.
// Losing this file costs one stale push target row on the server.
private val Context.pushDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PUSH_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class DataStorePushRegistrationStore(
    private val dataStore: DataStore<Preferences>,
) : PushRegistrationStore {
    /**
     * The constructor Hilt uses; the primary one takes the [DataStore] directly. A default
     * argument would not do — Dagger ignores Kotlin defaults and would demand a
     * `DataStore<Preferences>` binding that does not exist. Same shape as `DataStoreTokenStore`.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.pushDataStore)

    override suspend fun read(): PushRegistration? {
        val prefs =
            dataStore.data
                // The documented DataStore idiom: an unreadable file means "nothing registered",
                // not an IOException thrown out of a BroadcastReceiver's coroutine.
                .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
                .first()
        val id = prefs[TARGET_ID]
        val endpoint = prefs[ENDPOINT]
        // All or nothing: half a record cannot be acted on, and treating it as usable would mean
        // deleting an id we cannot match to the endpoint it belongs to.
        return if (id != null && endpoint != null) PushRegistration(targetId = id, endpoint = endpoint) else null
    }

    override suspend fun write(registration: PushRegistration) {
        dataStore.edit { prefs ->
            prefs[TARGET_ID] = registration.targetId
            prefs[ENDPOINT] = registration.endpoint
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(TARGET_ID)
            prefs.remove(ENDPOINT)
        }
    }

    override suspend fun readEndpoint(): String? =
        dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .first()[ENDPOINT]

    override suspend fun clearTarget() {
        dataStore.edit { prefs -> prefs.remove(TARGET_ID) }
    }

    private companion object {
        val TARGET_ID = stringPreferencesKey("push_target_id")
        val ENDPOINT = stringPreferencesKey("push_endpoint")
    }
}
