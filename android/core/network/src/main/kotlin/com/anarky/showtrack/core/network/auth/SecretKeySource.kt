package com.anarky.showtrack.core.network.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** Where [TokenCrypto] gets its key. A seam, so the cipher logic is testable off-device. */
fun interface SecretKeySource {
    fun key(): SecretKey
}

/**
 * An AES-256 key that never leaves the Android Keystore (decision A-I).
 * `EncryptedSharedPreferences` is deprecated and deliberately not used.
 */
@Singleton
class KeystoreSecretKeySource
    @Inject
    constructor() : SecretKeySource {
        /**
         * Synchronized because check-then-generate is not atomic and the callers are not
         * serialised: [TokenRefreshAuthenticator] can run `decrypt` on up to five OkHttp worker
         * threads at once. Two threads both missing `getEntry` would both generate under the same
         * alias, the second overwriting the first — after which everything encrypted with the
         * first key silently fails to decrypt and the user is logged out for no visible reason.
         * Reachable after a keystore reset or a device restore, which is exactly when the entry
         * is missing and traffic is in flight.
         */
        @Synchronized
        override fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(TokenCrypto.KEY_SIZE_BITS)
                    // No setUserAuthenticationRequired: a background token refresh must work with
                    // the screen locked, and requiring auth here would fail every one of them.
                    .build(),
            )
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "showtrack.tokens"
        }
    }
