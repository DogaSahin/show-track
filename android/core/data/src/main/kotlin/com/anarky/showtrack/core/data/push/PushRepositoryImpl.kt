package com.anarky.showtrack.core.data.push

import android.util.Log
import com.anarky.showtrack.core.model.PushNotification
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.PushPayloadDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The value the backend's `PushTransport` enum stores. A String because the API's field is one. */
internal const val TRANSPORT_UNIFIEDPUSH = "unifiedpush"

private const val TAG = "ShowTrackPush"

@Singleton
class PushRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
        private val store: PushRegistrationStore,
        private val json: Json,
    ) : PushRepository {
        override suspend fun register(endpoint: String) {
            if (store.read()?.endpoint == endpoint) return

            val created =
                api.registerPushTarget(
                    RegisterTargetRequest(
                        transport = TRANSPORT_UNIFIEDPUSH,
                        target = endpoint,
                        // Deliberately null. The label is for a human picking one of their own
                        // devices out of a list, and `android.os.Build.MODEL` is a marketing name
                        // ("SM-G991B"), not one. A future settings screen can let the user name
                        // the device; inventing one here would fill the list with strings nobody
                        // chose and nobody recognises.
                        label = null,
                    ),
                )
            // Written AFTER the call succeeds. Writing first would record an id the server never
            // issued if the POST failed, and the next `unregister()` would then DELETE a
            // fabricated id — a 404 the user cannot diagnose — while the real row lived on.
            store.write(PushRegistration(targetId = created.id, endpoint = endpoint))
        }

        override suspend fun unregister() {
            val registration = store.read() ?: return
            api.deletePushTarget(registration.targetId)
            // Cleared only after the DELETE returns. On a failure the record survives, so the
            // next unregister can try again — the alternative leaks a server row with no local
            // trace of it, permanently.
            store.clear()
        }

        /**
         * Best effort on the wire, unconditional locally — see [PushRepository.onLoggedOut].
         *
         * `catch (Exception)` around the DELETE and not around the `clear()`: the DELETE is
         * expected to fail on the commonest logout path, because a terminal refresh failure is
         * how the app learns it is logged out and the token is dead by then. Letting that abort
         * the local clear is exactly the bug this method exists to fix.
         *
         * The residual, stated rather than hidden: when the DELETE does fail, the server row
         * survives and points at an endpoint that still reaches this device. The next user's
         * registration then gets a 409 and this device receives the previous user's notifications
         * until that row is deleted or the distributor rotates the endpoint. Closing it properly
         * is a server-side question (whether a second user presenting the same endpoint should
         * TAKE OVER the row rather than be refused) and is flagged, not decided here.
         */
        @Suppress("TooGenericExceptionCaught")
        override suspend fun onLoggedOut() {
            val registration = store.read() ?: return
            try {
                api.deletePushTarget(registration.targetId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "could not delete the push target on logout: ${failure.javaClass.simpleName}")
            }
            store.clear()
        }

        /**
         * Catches `Exception`, not `Throwable`: an OOM or a StackOverflow is not something a
         * notification handler can recover from, and swallowing one would hide a real fault. The
         * failures actually expected are `SerializationException` (a payload that is not ours)
         * and `IllegalArgumentException` (malformed bytes) — both from a body another process
         * handed us, so neither is a bug on this side.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun decodeMessage(body: ByteArray): PushNotification? =
            try {
                val payload = json.decodeFromString<PushPayloadDto>(body.decodeToString())
                PushNotification(
                    title = payload.title,
                    body = payload.body,
                    mediaId = payload.mediaId,
                    episodeNumber = payload.episodeNumber,
                )
            } catch (failure: Exception) {
                null
            }
    }
