package com.anarky.showtrack

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.detailDeepLink
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest half of push, and the link an earlier version of this work wrongly recorded as
 * untestable.
 *
 * It IS testable: Robolectric loads `:app`'s **merged** manifest, which is where
 * `:feature:profile`'s receiver and permission land after the manifest merger runs. So the two
 * declarations that no other test can reach — the `showtrack://` intent filter on `MainActivity`
 * and the UnifiedPush receiver — are both queryable through a real `PackageManager` here.
 *
 * Why it matters more than the count of tests suggests: both failures are SILENT. A missing
 * intent filter means a notification tap opens the launcher with no error anywhere; a missing
 * receiver declaration means the distributor's broadcast reaches nobody and registration appears
 * to succeed while nothing is ever delivered. Neither shows up in logcat, and neither is visible
 * to `NavGraphRegistrationTest` (which checks the graph, not the door) or `PushNotifierTest`
 * (which checks the intent, not who answers it).
 *
 * `application = Application::class` keeps Robolectric from instantiating `ShowTrackApplication`,
 * whose `@HiltAndroidApp` component would stand up DataStore and the Keystore — and now also the
 * push session observer — for a test that needs none of it. `sdk = [35]` because Robolectric
 * ships no shadow jar for 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MergedManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun viewIntent(uri: String) =
        Intent(Intent.ACTION_VIEW, uri.toUri()).apply { setPackage(context.packageName) }

    @Test
    fun `an activity answers the deep link a push notification opens`() {
        val resolved =
            context.packageManager.queryIntentActivities(
                viewIntent(detailDeepLink("abc-123")),
                PackageManager.MATCH_DEFAULT_ONLY,
            )

        assertTrue(
            "no activity in the merged manifest answers ${detailDeepLink("abc-123")}. A push " +
                "notification's tap would open the launcher instead of the title, silently.",
            resolved.isNotEmpty(),
        )
    }

    /**
     * The negative control. Without it the assertion above would pass on a manifest that answered
     * every `ACTION_VIEW` — a `<data android:scheme="*">` typo, say — and prove nothing about the
     * scheme actually being ours.
     */
    @Test
    fun `an unknown scheme resolves to nothing`() {
        val resolved =
            context.packageManager.queryIntentActivities(
                viewIntent("nosuchscheme://detail/abc-123"),
                PackageManager.MATCH_DEFAULT_ONLY,
            )

        assertTrue("the deep-link filter is too broad: it answered a scheme we do not own", resolved.isEmpty())
    }

    @Test
    fun `the UnifiedPush receiver is declared for the distributor's broadcasts`() {
        // All four actions, not just NEW_ENDPOINT: a filter that lists three of them registers
        // successfully and then never delivers messages, or never learns it was unregistered.
        listOf(
            "org.unifiedpush.android.connector.NEW_ENDPOINT",
            "org.unifiedpush.android.connector.MESSAGE",
            "org.unifiedpush.android.connector.UNREGISTERED",
            "org.unifiedpush.android.connector.REGISTRATION_FAILED",
        ).forEach { action ->
            val resolved =
                context.packageManager.queryBroadcastReceivers(
                    Intent(action).setPackage(context.packageName),
                    0,
                )

            assertTrue(
                "no receiver in the merged manifest answers $action; the distributor's broadcast " +
                    "would reach nobody and registration would appear to succeed",
                resolved.any { it.activityInfo.name == RECEIVER },
            )
        }
    }

    @Test
    fun `the receiver is exported, because the distributor is another app`() {
        val resolved =
            context.packageManager
                .queryBroadcastReceivers(
                    Intent("org.unifiedpush.android.connector.NEW_ENDPOINT").setPackage(context.packageName),
                    0,
                ).single { it.activityInfo.name == RECEIVER }

        // Not a style check. An unexported receiver is unreachable from the distributor's process,
        // so registration would appear to succeed and no message would ever arrive.
        assertTrue(
            "the UnifiedPush receiver must be exported or the distributor cannot reach it",
            resolved.activityInfo.exported,
        )
    }

    private companion object {
        const val RECEIVER = "com.anarky.showtrack.feature.profile.push.ShowTrackMessagingReceiver"
    }
}
