package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.model.PushNotification

/**
 * Not exercised by [ProfileEntryHiltTest] at all — it exists only because replacing `:core:data`'s
 * `DataModule` (which normally binds the real [PushRepository]) drops that binding too, and
 * `push.PushEntryPoint` (an `@EntryPoint` reached by `MessagingReceiver`, a non-Hilt-managed class)
 * forces Dagger to validate the WHOLE `SingletonComponent`, not just what `ProfileViewModel`
 * actually asks for. Every member throws on purpose: a call reaching this would mean the test
 * exercised push behaviour it never intended to.
 */
internal class EntryFakePushRepository : PushRepository {
    override suspend fun register(endpoint: String) = error("ProfileEntryHiltTest does not exercise push")

    override suspend fun unregister() = error("ProfileEntryHiltTest does not exercise push")

    override suspend fun onLoggedOut() = error("ProfileEntryHiltTest does not exercise push")

    override suspend fun onLoggedIn() = error("ProfileEntryHiltTest does not exercise push")

    override fun decodeMessage(body: ByteArray): PushNotification? =
        error("ProfileEntryHiltTest does not exercise push")
}
