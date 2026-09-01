package com.anarky.showtrack.feature.profile.push

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because a real `Intent` parses its data through `android.net.Uri`, which is not a
 * JVM class. `sdk = [35]` because Robolectric ships no shadow jar for 36 — the same call
 * `:app`, `:core:data`, `:core:database` and `:core:network` already made.
 *
 * One of three tests that have to agree, because the deep link fails SILENTLY when they do not —
 * a wrong URI opens the launcher with no error anywhere. This pins the URI the notifier builds;
 * `:app`'s NavGraphRegistrationTest pins that the graph answers it; `:app`'s MergedManifestTest
 * pins the intent filter that lets it in at all. That last one was wrongly recorded as
 * untestable: Robolectric loads `:app`'s MERGED manifest, so a real PackageManager can be asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the tap intent addresses the title the notification is about`() {
        val intent = PushNotifier.deepLinkIntent(context, "abc-123")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        // The literal, spelled out on purpose. detailDeepLink() builds it from constants, so
        // asserting against those constants would pass after any of them changed — including a
        // change that silently stopped matching the manifest filter, which is a literal.
        assertEquals("showtrack://detail/abc-123", intent.data.toString())
    }

    @Test
    fun `the tap cannot be intercepted by another app claiming the scheme`() {
        // A custom scheme is unverifiable — any installed app may register an intent filter for
        // `showtrack://`. Constraining the intent to our own package is what stops a notification
        // tap from opening someone else's activity.
        val intent = PushNotifier.deepLinkIntent(context, "abc-123")

        assertEquals(context.packageName, intent.getPackage())
    }

    @Test
    fun `the tap does not stack a second copy of the same screen`() {
        val flags = PushNotifier.deepLinkIntent(context, "abc-123").flags

        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
