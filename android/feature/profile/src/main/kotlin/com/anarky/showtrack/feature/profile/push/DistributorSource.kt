package com.anarky.showtrack.feature.profile.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is a UnifiedPush distributor installed, and have we chosen one?"
 *
 * An interface over three static calls, and it earns its keep for one reason:
 * `UnifiedPush.getDistributors` is a `PackageManager` query against apps installed on the device,
 * so the branch that matters most — NO distributor at all — is unreachable from a JVM test
 * otherwise. That branch is the one that must never be silent (decision A-A): a user with no
 * distributor gets no notifications, and without the prompt the only available conclusion is
 * "push is broken" rather than "push needs one more app".
 */
interface DistributorSource {
    /** Package names of every installed app that can act as a distributor. Usually zero or one. */
    fun available(): List<String>

    /** The distributor already chosen for this app, or null. */
    fun selected(): String?

    /** Chooses [packageName] and asks it for an endpoint. Fires `onNewEndpoint` when it answers. */
    fun register(packageName: String)

    /** Gives up the registration. Fires `onUnregistered`. */
    fun unregister()
}

@Singleton
class UnifiedPushDistributorSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : DistributorSource {
        override fun available(): List<String> = UnifiedPush.getDistributors(context)

        override fun selected(): String? = UnifiedPush.getSavedDistributor(context)

        override fun register(packageName: String) {
            // saveDistributor BEFORE register: the connector reads the saved choice to decide
            // which app to send the REGISTER broadcast to, so registering first is a broadcast
            // to nobody — and a silent one, since there is no error to observe.
            UnifiedPush.saveDistributor(context, packageName)
            UnifiedPush.register(context)
        }

        override fun unregister() = UnifiedPush.unregister(context)
    }
