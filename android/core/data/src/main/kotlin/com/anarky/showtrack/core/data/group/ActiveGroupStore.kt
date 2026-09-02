package com.anarky.showtrack.core.data.group

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DataStore file for the switcher's own selection (design decision E-D). Separate from every
 * other store's file, mirroring [com.anarky.showtrack.core.data.push.PUSH_DATASTORE_NAME]'s own
 * reasoning: different lifetime, different secrecy.
 *
 * PUBLIC for the same reason `PUSH_DATASTORE_NAME` is: it names the file a backup-exclusion test
 * would otherwise have nothing to check by name alone. **This one is deliberately NOT added to
 * `app/src/main/res/xml/backup_rules.xml` / `data_extraction_rules.xml`** — see the KDoc on
 * [DataStoreActiveGroupStore] for why that omission is a decision, not an oversight.
 */
const val ACTIVE_GROUP_DATASTORE_NAME = "showtrack_active_group"

/**
 * Mirrors `PushRegistrationStore`, the existing DataStore-backed store in `:core:data` (design
 * decision E-D): the selection must survive a cold start, `:app` may depend on `:core:data`
 * (architecture rule 2 constrains `:feature:*`, not `:app`), and following the existing store's
 * shape means no new persistence pattern enters the codebase. Rejected: Room — the active group is
 * one nullable id, not a queryable relation, and Room would put a migration in the way of a
 * preference.
 *
 * Unlike [com.anarky.showtrack.core.data.push.PushRegistrationStore], this exposes a [Flow]
 * directly rather than a suspend `read()`: the switcher's whole point (E-C) is that every screen
 * scoped to the active group reacts the moment it changes, which a one-shot read cannot express.
 */
interface ActiveGroupStore {
    val activeGroupId: Flow<String?>

    suspend fun setActiveGroup(groupId: String?)
}

// A top-level delegate, which is how DataStore enforces one instance per file per process —
// constructing two over the same file throws. The corruption handler covers WRITES as well as
// reads, the same reasoning `pushDataStore`'s own comment gives: without it a damaged file would
// make `setActiveGroup(null)` throw too, leaving no in-app recovery. Losing this file costs one
// group switch back to "no active group" on next launch — harmless, unlike a lost push target.
//
// Not separately unit-tested: `ActiveGroupStoreTest`'s own corruption test measured that
// hand-crafted garbage bytes do not reliably reach `CorruptionException` at all — protobuf-lite's
// parser is lenient about a leading zero byte and about varint overflow — so there is no
// inexpensive way to drive this specific handler from a JVM test. It is included on the same
// belt-and-braces reasoning `PushRegistrationStore`'s copy is: cheap insurance against a real
// on-disk corruption, not a behaviour this task claims to have proven.
private val Context.activeGroupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ACTIVE_GROUP_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class DataStoreActiveGroupStore(
    private val dataStore: DataStore<Preferences>,
) : ActiveGroupStore {
    /**
     * The constructor Hilt uses; the primary one takes the [DataStore] directly, matching
     * `DataStorePushRegistrationStore`'s own shape and for the same reason: a default argument
     * would not do, since Dagger ignores Kotlin defaults and would demand a `DataStore<Preferences>`
     * binding that does not exist.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.activeGroupDataStore)

    // NOT excluded from Android auto-backup, unlike the token store and the push store — and that
    // is a decision, not an oversight (the task brief calls this out explicitly, because
    // `TokenBackupExclusionTest` only asserts the two exclusions that already exist and would not
    // catch a wrong choice here in either direction). The token store is excluded because an
    // undecryptable ciphertext survives a restore onto a device whose Keystore never held the key;
    // the push store is excluded because its target id identifies THIS DEVICE's push registration,
    // which a restore onto a different device would misrepresent. An active group id is neither: it
    // is a harmless preference, and restoring it onto a new phone — reopening the app to the same
    // group you were last looking at — is the CORRECT behaviour, not a bug to guard against.
    override val activeGroupId: Flow<String?> =
        dataStore.data
            // The documented DataStore idiom, matching `DataStorePushRegistrationStore.read()`: an
            // unreadable file means "no active group", not an IOException thrown out of whatever
            // collects this at app start.
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> prefs[ACTIVE_GROUP_ID] }

    override suspend fun setActiveGroup(groupId: String?) {
        dataStore.edit { prefs ->
            if (groupId == null) prefs.remove(ACTIVE_GROUP_ID) else prefs[ACTIVE_GROUP_ID] = groupId
        }
    }

    private companion object {
        val ACTIVE_GROUP_ID = stringPreferencesKey("active_group_id")
    }
}
