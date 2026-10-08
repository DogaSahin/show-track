package com.anarky.showtrack

import android.app.Application
import android.content.ComponentName
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
 * The manifest half of episode alerts.
 *
 * Robolectric loads `:app`'s **merged** manifest, which is where the libraries' declarations land
 * after the manifest merger runs. So two things no other test can reach are queryable through a
 * real `PackageManager` here: the `showtrack://` intent filter on `MainActivity`, and the removal
 * of WorkManager's default initializer.
 *
 * Both failures are SILENT. A missing intent filter means an alert's tap opens the launcher with
 * no error anywhere; a default-initialised WorkManager cannot build the alert workers. Neither is
 * visible to `NavGraphRegistrationTest` (which checks the graph, not the door) or
 * `EpisodeAlertNotifierTest` (which checks the intent, not who answers it).
 *
 * `application = Application::class` keeps Robolectric from instantiating `ShowTrackApplication`,
 * whose `@HiltAndroidApp` component would stand up DataStore and the Keystore for a test that
 * needs none of it. `sdk = [35]` because Robolectric
 * ships no shadow jar for 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MergedManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun viewIntent(uri: String) =
        Intent(Intent.ACTION_VIEW, uri.toUri()).apply { setPackage(context.packageName) }

    @Test
    fun `an activity answers the deep link an episode alert opens`() {
        val resolved =
            context.packageManager.queryIntentActivities(
                viewIntent(detailDeepLink("abc-123")),
                PackageManager.MATCH_DEFAULT_ONLY,
            )

        assertTrue(
            "no activity in the merged manifest answers ${detailDeepLink("abc-123")}. An episode " +
                "alert's tap would open the launcher instead of the title, silently.",
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

    /**
     * The app hands WorkManager Hilt's worker factory (ShowTrackApplication is its
     * Configuration.Provider). If WorkManager's own startup initializer were still merged in, it
     * would initialise first with the default factory, and every episode-alert worker would fail
     * to construct, silently, at the moment it was meant to fire.
     */
    @Test
    fun `WorkManager's default initializer is removed, so the app's worker factory is used`() {
        val provider =
            context.packageManager.getProviderInfo(
                ComponentName(context, "androidx.startup.InitializationProvider"),
                PackageManager.GET_META_DATA,
            )

        assertTrue(
            "androidx.work.WorkManagerInitializer is still in the merged manifest",
            provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") != true,
        )
    }
}
