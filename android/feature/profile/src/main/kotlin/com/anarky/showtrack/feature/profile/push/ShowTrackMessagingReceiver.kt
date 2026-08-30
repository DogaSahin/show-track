package com.anarky.showtrack.feature.profile.push

import android.content.Context
import android.util.Log
import com.anarky.showtrack.core.data.push.PushRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * How a `BroadcastReceiver` reaches the Hilt graph.
 *
 * `@EntryPoint` rather than `@AndroidEntryPoint`: Hilt's receiver support injects during
 * `onReceive`, and this class does not override `onReceive` — the UnifiedPush base class does,
 * and it is the base class that parses the intent and dispatches to the four callbacks below.
 * Pulling the dependencies at the point of use sidesteps that ordering question entirely and
 * keeps this class constructible by anything (the system, in practice) rather than only by
 * generated code.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PushEntryPoint {
    fun pushRegistrar(): PushRegistrar

    fun pushRepository(): PushRepository
}

/**
 * The distributor's four callbacks, and the only Android component in this feature that runs
 * without a screen.
 *
 * It lives in `:feature:profile` because that is where push is CONFIGURED — the screen that
 * offers to enable it owns the component that receives it. It reaches the network the way every
 * feature does, through `:core:data`, so architecture rule 2 holds even here: this file cannot
 * name Retrofit, and the JSON in `onMessage` is decoded behind [PushRepository.decodeMessage].
 */
class ShowTrackMessagingReceiver : MessagingReceiver() {
    /**
     * Fires on EVERY app start, not once — the distributor re-delivers the endpoint it holds
     * whenever the app re-registers. That is precisely why the backend's registration is
     * idempotent on the endpoint value (decision A-O): without it, one cold start per day would
     * add one push target per day, and a single episode would then arrive N times on one phone.
     */
    override fun onNewEndpoint(
        context: Context,
        endpoint: PushEndpoint,
        instance: String,
    ) = inBackground(context) { registrar -> registrar.submit(endpoint.url) }

    /**
     * The whole reason this transport exists rather than reusing ntfy's own title/message format
     * (backend 6-O): the body is OUR JSON, so it can carry `media_id` and the tap can open the
     * title the notification is about.
     *
     * A payload that does not decode is dropped silently — [PushRepository.decodeMessage] returns
     * null rather than throwing, because the bytes come from another process and an exception
     * here is a process death, not an error message.
     */
    override fun onMessage(
        context: Context,
        message: PushMessage,
        instance: String,
    ) {
        val notification = entryPoint(context).pushRepository().decodeMessage(message.content) ?: return
        PushNotifier.show(context, notification)
    }

    /**
     * The user removed the distributor, or it dropped this registration. Deleting the row is not
     * housekeeping: the backend would otherwise keep POSTing to an endpoint nothing answers, and
     * would only prune it after five attempts turned into a 404.
     */
    override fun onUnregistered(
        context: Context,
        instance: String,
    ) = inBackground(context) { registrar -> registrar.delete() }

    /**
     * Logged, not surfaced. There is no screen attached to a broadcast, and the profile screen
     * already shows the state that matters ("no distributor installed"). The reason is logged
     * because `ACTION_REQUIRED` — the distributor needs the user to do something in ITS UI — is
     * otherwise indistinguishable from a network blip from outside.
     */
    override fun onRegistrationFailed(
        context: Context,
        reason: FailedReason,
        instance: String,
    ) {
        Log.w("ShowTrackPush", "distributor refused the registration: $reason")
    }

    /**
     * `goAsync()`, not a fire-and-forget launch on an application scope.
     *
     * A `BroadcastReceiver` is considered finished the moment its callback returns, and the
     * system is then free to kill a process that has nothing else keeping it alive — which is
     * the normal state when a push arrives at 3am. A coroutine launched without this races that
     * teardown and loses often enough to be a bug nobody can reproduce. `PendingResult.finish()`
     * is what tells the system the work is actually done, and it MUST be called or the process is
     * held awake (there is a ~10s ceiling either way, which is ample for one HTTP call).
     *
     * `finish()` sits in a `finally` so a throw cannot leak the pending result. The block itself
     * does not throw — [PushRegistrar] contains its own failures — but that is its guarantee to
     * keep, not an assumption to build on.
     */
    private fun inBackground(
        context: Context,
        block: suspend (PushRegistrar) -> Unit,
    ) {
        val registrar = entryPoint(context).pushRegistrar()
        val pending = goAsync()
        // A scope per broadcast, and a short-lived one: it exists only until finish() runs. An
        // application-wide scope would outlive the PendingResult and lose the guarantee above.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block(registrar)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * `applicationContext`, always: the `Context` handed to a receiver is a `ReceiverRestrictedContext`
 * whose lifetime ends with the callback, and Hilt's component is held by the Application.
 */
private fun entryPoint(context: Context): PushEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, PushEntryPoint::class.java)
