package com.anarky.showtrack.core.data.push

import com.anarky.showtrack.core.model.PushNotification

/**
 * The push side of the data layer, and — like [com.anarky.showtrack.core.data.repository.LibraryRepository]
 * — the only type `:feature:profile` may name for it. Retrofit, the DTOs and the JSON parser all
 * stop here (architecture rule 2), which is what lets a `BroadcastReceiver` in a feature module
 * handle a wire payload without ever seeing the wire format.
 */
interface PushRepository {
    /**
     * Registers [endpoint] with the backend, or does nothing if this exact endpoint is already
     * registered from this install.
     *
     * The local skip is an OPTIMISATION, never the guarantee: `onNewEndpoint` fires on every app
     * start, and clearing app data or reinstalling would defeat any client-side memory. The
     * server is idempotent on the endpoint (decision A-O) and that is what actually stops one
     * episode becoming N notifications.
     */
    suspend fun register(endpoint: String)

    /**
     * Deletes this device's target, if one was registered. A no-op when nothing is stored, so
     * a duplicate `onUnregistered` is harmless.
     */
    suspend fun unregister()

    /**
     * The user's session ended. Deletes this device's target if it still can, and **always**
     * forgets it locally.
     *
     * Distinct from [unregister], and the difference is the whole point. Without it, a shared
     * device leaks notifications across accounts: A logs out, B logs in, `onNewEndpoint` delivers
     * the SAME endpoint, [register]'s local skip sees it unchanged and posts nothing — so A's row
     * still points at that device and **B receives A's notifications**.
     *
     * This is the client half of a two-sided fix. The server half is that a registration for an
     * endpoint owned by someone else now REASSIGNS that row and answers 200, rather than
     * refusing it — so B's POST succeeds even when A's row was never deleted.
     *
     * "Always forgets it locally" is the part that must not be conditional. The commonest logout
     * here is a TERMINAL REFRESH FAILURE, i.e. the token is already dead — so the DELETE will
     * fail, and keeping the record on failure (which is right for [unregister]) would leave the
     * next user permanently blocked by our own skip.
     */
    suspend fun onLoggedOut()

    /**
     * Decodes a UnifiedPush message body into the domain type, or null if it is not one of ours.
     *
     * Null rather than throwing: this runs inside a `BroadcastReceiver`, where an exception is a
     * crash rather than an error message, and the input is a byte array from another process.
     * A distributor that delivers a keepalive, a truncated body, or someone else's payload must
     * make the notification not appear — not make the app die.
     */
    fun decodeMessage(body: ByteArray): PushNotification?
}
