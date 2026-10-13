package com.anarky.showtrack.core.network.auth

data class TokenPair(
    val access: String,
    val refresh: String,
)

/**
 * Where the token pair lives.
 *
 * An interface rather than a concrete class because the only implementation talks to DataStore
 * and the Android Keystore, neither of which exists in a JVM unit test — the single-flight
 * behaviour that actually needs testing would otherwise need a device.
 */
interface TokenStore {
    suspend fun tokens(): TokenPair?

    suspend fun save(
        access: String,
        refresh: String,
    )

    /**
     * Saves the pair only if the stored refresh token is still [expectedRefresh]; true if it
     * wrote. The refresh path saves through this so a refresh that finishes after sign-out (or
     * after a new login) cannot put a session back.
     */
    suspend fun replace(
        expectedRefresh: String,
        access: String,
        refresh: String,
    ): Boolean

    suspend fun clear()
}
