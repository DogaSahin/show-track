package com.anarky.showtrack.core.network.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// A top-level delegate, which is how DataStore enforces one instance per file per process:
// constructing two DataStores over the same file throws, and that is exactly the bug a
// per-injection factory would create.
private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "showtrack_tokens")

/**
 * DataStore for the storage, [TokenCrypto] for the confidentiality (decision A-I).
 *
 * DataStore itself is a plain file in app-private storage. That is already unreadable by other
 * apps, so the encryption is defence against a rooted or backed-up device rather than against
 * the sandbox — which is why a decrypt failure is treated as "no tokens" and not as an error.
 */
@Singleton
class DataStoreTokenStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val crypto: TokenCrypto,
    ) : TokenStore {
        override suspend fun tokens(): TokenPair? {
            val prefs = context.tokenDataStore.data.first()
            val access = prefs[ACCESS_KEY]?.let(crypto::decrypt)
            val refresh = prefs[REFRESH_KEY]?.let(crypto::decrypt)
            // All or nothing: half a pair is unusable, and treating it as usable would send a
            // request that can only 401 into a refresh that can only fail.
            return if (access != null && refresh != null) TokenPair(access, refresh) else null
        }

        override suspend fun save(
            access: String,
            refresh: String,
        ) {
            // One edit for both, so a crash between the two cannot leave an access token paired
            // with the previous refresh token — DataStore's edit is atomic over the whole file.
            val encryptedAccess = crypto.encrypt(access)
            val encryptedRefresh = crypto.encrypt(refresh)
            context.tokenDataStore.edit { prefs ->
                prefs[ACCESS_KEY] = encryptedAccess
                prefs[REFRESH_KEY] = encryptedRefresh
            }
        }

        override suspend fun clear() {
            context.tokenDataStore.edit { it.clear() }
        }

        private companion object {
            val ACCESS_KEY = stringPreferencesKey("access_token")
            val REFRESH_KEY = stringPreferencesKey("refresh_token")
        }
    }
