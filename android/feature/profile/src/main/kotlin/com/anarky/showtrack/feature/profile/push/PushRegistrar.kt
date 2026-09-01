package com.anarky.showtrack.feature.profile.push

import android.util.Log
import com.anarky.showtrack.core.data.push.PushRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowTrackPush"

/**
 * The two calls the distributor's callbacks make, with their failures contained.
 *
 * A class rather than two calls written inline in [ShowTrackMessagingReceiver], for one reason:
 * a `BroadcastReceiver` is not constructible by a test — the system instantiates it — so anything
 * living inside it can only be checked by reading. This can be handed a fake [PushRepository]
 * and driven directly, which is what makes "registration failure does not crash the receiver" an
 * assertion instead of a hope.
 *
 * Both methods are `suspend` and neither launches anything itself. The receiver owns the
 * lifetime, because only the receiver can call `goAsync()` — a coroutine launched on a scope this
 * class held would be racing the system's teardown of the receiver's process.
 */
@Singleton
class PushRegistrar
    @Inject
    constructor(
        private val repository: PushRepository,
    ) {
        /**
         * Called from `onNewEndpoint`, which fires on EVERY app start rather than once. The
         * server's registration is idempotent on the endpoint for exactly that reason (decision
         * A-O), so this is safe to call repeatedly and deliberately does not try to be clever
         * about when it is a "new" endpoint.
         */
        suspend fun submit(endpoint: String) = guard("register") { repository.register(endpoint) }

        /** Called from `onUnregistered`. A no-op when nothing was ever registered. */
        suspend fun delete() = guard("unregister") { repository.unregister() }

        /**
         * Failures are logged and swallowed, which is the right posture HERE and would not be in
         * a ViewModel. There is no user waiting on this and no screen to show an error on: the
         * caller is a broadcast from another process, and an exception escaping it takes the
         * whole app's process down with a "keeps stopping" dialog for something the user did not
         * do. A failed registration is retried by the next `onNewEndpoint`, which is the next app
         * start.
         *
         * `CancellationException` is rethrown rather than logged: it is structured concurrency's
         * control flow, not a failure, and a coroutine that swallows its own cancellation stops
         * its parent from ever completing. This is also why `runCatching` is not used — it
         * catches `Throwable`, cancellation included.
         *
         * The message is never logged with the endpoint in it. The endpoint is a bearer secret in
         * the same sense the backend treats it (6-L): anyone holding it can push arbitrary
         * notifications to this device, and logcat is readable by adb.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun guard(
            what: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.w(TAG, "push $what failed: ${failure.javaClass.simpleName}")
            }
        }
    }
