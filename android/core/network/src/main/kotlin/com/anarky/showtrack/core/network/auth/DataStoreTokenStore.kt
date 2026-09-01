package com.anarky.showtrack.core.network.auth

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
import kotlin.coroutines.cancellation.CancellationException

/**
 * The DataStore file's name, public because `:app` needs it: the token file must be EXCLUDED from
 * Auto Backup and device transfer, and those exclusions are paths in `res/xml`, in another module,
 * that nothing but a test can keep in step with this string. `TokenBackupExclusionTest` in `:app`
 * reads both rule files and asserts they exclude `datastore/$TOKEN_DATASTORE_NAME.preferences_pb`,
 * so renaming this fails a build instead of silently restoring the exclusion to nothing.
 *
 * Why exclude at all, given the contents are AES-GCM ciphertext: the key lives in the Android
 * Keystore and is not backed up, by design. So a restored file can never decrypt — it is a
 * credential-shaped blob in the user's cloud with no upside and a small downside.
 */
const val TOKEN_DATASTORE_NAME = "showtrack_tokens"

// A top-level delegate, which is how DataStore enforces one instance per file per process:
// constructing two DataStores over the same file throws, and that is exactly the bug a
// per-injection factory would create.
//
// The corruption handler is the half of the story `.catch` on the read flow cannot cover. A
// CorruptionException is raised on WRITES too, so without this a damaged file makes `clear()`
// throw as well — leaving the app with no in-app recovery at all, only "clear app data". Losing
// the file replaces two tokens the user can re-mint by logging in.
private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TOKEN_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DataStore for the storage, [TokenCrypto] for the confidentiality (decision A-I).
 *
 * DataStore itself is a plain file in app-private storage. That is already unreadable by other
 * apps, so the encryption is defence against a rooted or backed-up device rather than against
 * the sandbox — which is why a decrypt failure is treated as "no tokens" and not as an error.
 */
@Singleton
class DataStoreTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: TokenCrypto,
) : TokenStore {
    /**
     * The constructor Hilt uses. The primary one takes the [DataStore] directly, which is the
     * seam: [tokenDataStore] is a top-level `Context` extension delegate, so without this a test
     * cannot supply a store whose writes fail — and the crash-safety of [dropUndecryptable] is
     * exactly the behaviour that needs a failing write to observe.
     *
     * A default argument would not do: Dagger ignores Kotlin default values and would demand a
     * `DataStore<Preferences>` binding that does not exist.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        crypto: TokenCrypto,
    ) : this(context.tokenDataStore, crypto)

    override suspend fun tokens(): TokenPair? {
        val prefs =
            dataStore.data
                // The documented DataStore idiom. An unreadable file means "no tokens", i.e.
                // log in again — not an IOException thrown from an OkHttp worker thread,
                // where an uncaught throwable takes the process down with it.
                .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
                .first()
        // All or nothing: half a pair is unusable, and treating it as usable would send a
        // request that can only 401 into a refresh that can only fail.
        val pair = decrypt(prefs)
        // Ciphertext that is present but unreadable is the case worth cleaning up. Nothing stored
        // at all is the ordinary logged-out case, and skipping `edit` for it keeps the common
        // path free of both a write and DataStore's write lock.
        val hasUnreadableCiphertext =
            pair == null && (prefs[ACCESS_KEY] != null || prefs[REFRESH_KEY] != null)
        if (hasUnreadableCiphertext) dropUndecryptable()
        return pair
    }

    /**
     * Ciphertext present that will not decrypt means the key it was written under is gone — a
     * Keystore reset, or a cloud restore onto a device whose Keystore never held it. That blob
     * can NEVER become readable, so keeping it means re-reading and re-failing on every launch
     * forever, which presents to the user as being silently logged out for good. Dropping it
     * costs a login they already owe and leaves the store in a state `save()` can write cleanly.
     *
     * The check is repeated INSIDE `edit` rather than trusting the snapshot [tokens] already
     * read. `edit` is the only place DataStore serialises against concurrent writers, and the
     * snapshot was taken across a suspension point: a `save()` landing in that gap would
     * otherwise be wiped by this cleanup, logging the user out immediately after a successful
     * login.
     *
     * `try`/`catch (Exception)` rather than `runCatching`, matching `LibraryViewModel.guard`:
     * runCatching swallows [kotlinx.coroutines.CancellationException] too, which is structured
     * concurrency's own control flow. Catching at all is the point though, and it is easy to miss
     * because this sits under a READ: `edit` is a disk write that can fail, `AuthInterceptor`
     * calls `tokens()` from `runBlocking` on an OkHttp worker thread with no catch of its own,
     * and an IOException escaping there takes the process down. Same reasoning as
     * `TokenRefreshAuthenticator.clearQuietly`. The caller has already been told "no tokens"
     * either way, so a failed cleanup changes nothing it could have acted on.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun dropUndecryptable() {
        try {
            dataStore.edit { prefs ->
                val stillStored = prefs[ACCESS_KEY] != null || prefs[REFRESH_KEY] != null
                if (stillStored && decrypt(prefs) == null) prefs.clear()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Deliberately swallowed; see the KDoc above.
        }
    }

    private fun decrypt(prefs: Preferences): TokenPair? {
        val access = prefs[ACCESS_KEY]?.let(crypto::decrypt)
        val refresh = prefs[REFRESH_KEY]?.let(crypto::decrypt)
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
        dataStore.edit { prefs ->
            prefs[ACCESS_KEY] = encryptedAccess
            prefs[REFRESH_KEY] = encryptedRefresh
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val ACCESS_KEY = stringPreferencesKey("access_token")
        val REFRESH_KEY = stringPreferencesKey("refresh_token")
    }
}
