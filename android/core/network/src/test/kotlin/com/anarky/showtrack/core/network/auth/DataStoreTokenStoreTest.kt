package com.anarky.showtrack.core.network.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * The store's own logic, on the JVM.
 *
 * Robolectric rather than an `androidTest`, deliberately: everything asserted here needs a
 * `Context` and a file, not the Android Keystore, so it can be EXECUTED by
 * `./gradlew testDebugUnitTest` instead of merely compiled by `assembleDebugAndroidTest`. The
 * genuinely device-only parts — whether the Keystore accepts the `KeyGenParameterSpec`, and
 * whether the key survives into a later process — stay in [TokenStoreInstrumentationTest], which
 * has no off-device stand-in.
 *
 * One test method on purpose: the `preferencesDataStore` delegate caches a single DataStore
 * instance against the first `Context` it sees, and Robolectric hands out a fresh `filesDir` per
 * test, so a second method in this class would be operating on a DataStore pointed at a directory
 * that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreTokenStoreTest {
    /**
     * A Keystore reset — or a cloud restore onto a device whose Keystore never held this key —
     * leaves ciphertext that can NEVER be decrypted again. Reading it as "no tokens" is correct
     * but not sufficient: left in place, the blob is re-read and re-fails on every launch, which
     * presents to the user as being silently logged out forever.
     *
     * A second key source stands in for the reset, which is the only way to reach that state
     * without wiping a real device's Keystore.
     */
    @Test
    fun `undecryptable tokens are dropped rather than re-read forever`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // The key is hoisted, NOT `TokenCrypto { newKey() }`: SecretKeySource.key() is
            // invoked on every encrypt and every decrypt, so a generating lambda would hand out a
            // different key each call and nothing would ever round-trip. KeystoreSecretKeySource
            // returns the same key each time, and a fake has to model that.
            val liveKey = newKey()
            val store = DataStoreTokenStore(context, TokenCrypto { liveKey })

            store.save(access = "access-1", refresh = "refresh-1")
            assertEquals(
                "precondition: the pair must be readable before the key changes",
                TokenPair("access-1", "refresh-1"),
                store.tokens(),
            )

            val strandedKey = newKey()
            val afterKeystoreReset = DataStoreTokenStore(context, TokenCrypto { strandedKey })
            assertNull("undecryptable ciphertext must read as logged out", afterKeystoreReset.tokens())

            // The actual point: not that the read returned null, but that the unusable blob is
            // GONE. Re-reading through the ORIGINAL, still-working crypto is what distinguishes
            // "cleared" from "skipped" — a store that merely ignored it would answer here too.
            assertNull("the unusable ciphertext must have been cleared, not just ignored", store.tokens())
        }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(TokenCrypto.KEY_SIZE_BITS) }.generateKey()
}
