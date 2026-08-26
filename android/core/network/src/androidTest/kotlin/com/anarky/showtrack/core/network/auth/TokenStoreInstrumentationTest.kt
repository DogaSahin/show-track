package com.anarky.showtrack.core.network.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The half of the token path that cannot be proved on the JVM.
 *
 * `TokenCryptoTest` covers the cipher, the IV and the Base64 round trip with an in-memory key.
 * What is left is device-only and is exactly where a silent logout comes from: whether the
 * Android Keystore ACCEPTS this `KeyGenParameterSpec` at all, whether the generated key survives
 * being fetched again in a later process, and whether DataStore round-trips what was written.
 * None of that has an off-device stand-in — Robolectric ships no AndroidKeyStore provider.
 *
 * **Not in the gate.** It needs a connected device or emulator
 * (`./gradlew :core:network:connectedDebugAndroidTest`). CI compiles it via
 * `assembleDebugAndroidTest` so it cannot rot unnoticed, but does not run it — there is no
 * emulator in the pipeline.
 */
@RunWith(AndroidJUnit4::class)
class TokenStoreInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crypto = TokenCrypto(KeystoreSecretKeySource())
    private val store = DataStoreTokenStore(context, crypto)

    @After
    fun tearDown() =
        runBlocking {
            store.clear()
        }

    /** Generation and retrieval are separate paths through `key()`; both have to hit the same key. */
    @Test
    fun keystore_backed_crypto_round_trips() {
        val token = "header.payload.signature"

        assertEquals(token, crypto.decrypt(crypto.encrypt(token)))
        // A second instance re-reads the alias rather than generating, which is the path every
        // launch after the first one takes.
        assertEquals(token, TokenCrypto(KeystoreSecretKeySource()).decrypt(crypto.encrypt(token)))
    }

    @Test
    fun tokens_survive_save_and_read_back() =
        runBlocking {
            store.save(access = "access-1", refresh = "refresh-1")

            assertEquals(TokenPair("access-1", "refresh-1"), store.tokens())

            store.clear()
            assertNull(store.tokens())
        }

    /**
     * The reason any of this encryption exists. Without it the file is plaintext credentials in
     * a backup or on a rooted device — and a bug that quietly skipped `crypto.encrypt` would
     * still pass the round-trip test above.
     */
    @Test
    fun the_stored_file_does_not_contain_the_plaintext_token() =
        runBlocking {
            val token = "a-very-recognisable-access-token"
            store.save(access = token, refresh = "refresh-1")

            val file = File(context.filesDir, "datastore/showtrack_tokens.preferences_pb")
            assertTrue("expected DataStore to have written $file", file.exists())
            val contents = file.readBytes().toString(Charsets.ISO_8859_1)

            assertFalse("the access token is on disk in the clear", contents.contains(token))
            assertNotNull(store.tokens())
        }
}
