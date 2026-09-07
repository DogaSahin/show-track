package com.anarky.showtrack.core.network.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The token-persistence path used to have no test at all, on either side of the device line —
 * and its failure mode is the quietest one in the app. A wrong IV length, or an encoder and a
 * decoder that disagree about padding or line wrapping, does not crash and does not log: it
 * makes `decrypt` return null, which the store reads as "no tokens", which the app reads as
 * "logged out". The user simply finds themselves signed out on every launch.
 *
 * Everything except the Keystore key itself is plain JCE, so all of that is provable here with
 * an in-memory AES-256 key. `KeystoreSecretKeySource` — the one part that genuinely needs a
 * device — is covered by the instrumentation test alongside this one.
 */
class TokenCryptoTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(TokenCrypto.KEY_SIZE_BITS) }.generateKey()
    private val crypto = TokenCrypto { key }

    @Test
    fun `a token survives a round trip`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload-with-dots.and-dashes_and_underscores"

        assertEquals(token, crypto.decrypt(crypto.encrypt(token)))
    }

    @Test
    fun `a non-ascii token survives a round trip`() {
        // The store holds whatever the server minted; UTF-8 in and UTF-8 out has to be exact.
        val token = "カウボーイビバップ ✓ émoji 🎬"

        assertEquals(token, crypto.decrypt(crypto.encrypt(token)))
    }

    /**
     * A reused IV is the one way to destroy GCM outright, and nothing in the code makes the IV
     * visibly unique — the provider does. This is the assertion that would notice if a future
     * change started passing a fixed `GCMParameterSpec` into ENCRYPT_MODE.
     */
    @Test
    fun `each encryption uses a fresh IV`() {
        val first = crypto.encrypt("same-token")
        val second = crypto.encrypt("same-token")

        assertNotEquals(first, second)
        assertNotEquals(ivOf(first).toList(), ivOf(second).toList())
        assertEquals("same-token", crypto.decrypt(first))
        assertEquals("same-token", crypto.decrypt(second))
    }

    @Test
    fun `a tampered ciphertext decrypts to null rather than to garbage`() {
        val bytes = Base64.getDecoder().decode(crypto.encrypt("token"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        // GCM authenticates, so this is a null and not a corrupted string that gets sent as a
        // Bearer token.
        assertNull(crypto.decrypt(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `a value written by a different key decrypts to null`() {
        val other = KeyGenerator.getInstance("AES").apply { init(TokenCrypto.KEY_SIZE_BITS) }.generateKey()
        val written = TokenCrypto { other }.encrypt("token")

        // The keystore-was-reset case: the file survives, the key does not.
        assertNull(crypto.decrypt(written))
    }

    @Test
    fun `malformed stored values decrypt to null rather than throwing`() {
        // Reached from an OkHttp worker thread, where an uncaught throwable ends the process.
        assertNull(crypto.decrypt(""))
        assertNull(crypto.decrypt("not base64 at all !!"))
        // Valid Base64, but too short to hold an IV plus a GCM tag.
        assertNull(crypto.decrypt(Base64.getEncoder().encodeToString(ByteArray(4))))
    }

    private fun ivOf(encoded: String): ByteArray = Base64.getDecoder().decode(encoded).copyOfRange(0, IV_LENGTH)

    private companion object {
        const val IV_LENGTH = 12
    }
}
