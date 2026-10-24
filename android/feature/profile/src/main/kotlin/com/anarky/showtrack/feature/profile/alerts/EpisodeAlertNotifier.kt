package com.anarky.showtrack.feature.profile.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.anarky.showtrack.core.navigation.detailDeepLink
import com.anarky.showtrack.feature.profile.R

/** The channel every episode alert goes to. */
private const val AIRING_CHANNEL_ID: String = "showtrack_airing"

/**
 * Posts an episode alert whose tap opens the title.
 *
 * An object handed a `Context`, so a Robolectric test can assert [deepLinkIntent]: the deep link
 * is the half of this feature that fails silently when it is wrong (a bad URI matches no
 * destination, the tap opens the start screen, and nothing anywhere reports it).
 */
object EpisodeAlertNotifier {
    /**
     * The `Intent` a tap resolves to. Internal and separate from [show] so a test can assert the
     * URI without standing up a NotificationManager.
     *
     * `setPackage`, not an explicit `ComponentName`: naming the activity would mean naming `:app`
     * from a `:feature:` module, which is the dependency direction the whole nav-graph design
     * exists to avoid. Constraining the intent to our own package is what stops another app that
     * has claimed `showtrack://` from receiving this tap.
     *
     * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` is the notification-tap convention:
     * NEW_TASK because a notification is not launched from an activity context, CLEAR_TOP so a
     * second notification for the same title does not stack another copy of Detail on the back
     * stack.
     */
    internal fun deepLinkIntent(
        context: Context,
        mediaId: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, detailDeepLink(mediaId).toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Whether a posted alert would actually show: false when the user blocked this app's
     * notifications, and on API 33+ also while the runtime permission is not granted (a post
     * without it is silently DROPPED, with nothing in logcat).
     */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            // The user can block just this channel (long-press an alert, "Turn off").
            NotificationManagerCompat.from(context).getNotificationChannelCompat(AIRING_CHANNEL_ID)?.importance !=
            NotificationManagerCompat.IMPORTANCE_NONE &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            )

    fun show(
        context: Context,
        mediaId: String,
        title: String,
        text: String,
    ) {
        // Spelled out rather than calling canNotify() so lint can see the check guarding notify().
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                // Per title, so two shows' notifications do not share one PendingIntent. With a
                // constant request code, FLAG_UPDATE_CURRENT would rewrite the FIRST
                // notification's intent to point at the SECOND title — the tap then opens the
                // wrong show, which is the kind of bug that looks like a backend mix-up.
                mediaId.hashCode(),
                deepLinkIntent(context, mediaId),
                // IMMUTABLE is mandatory on API 31+ and correct everywhere: a mutable
                // PendingIntent handed to the system notification shade is a capability another
                // process can rewrite. UPDATE_CURRENT so a re-delivered notification for the same
                // title refreshes rather than resurrecting a stale extra.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        NotificationManagerCompat.from(context).notify(
            mediaId.hashCode(),
            NotificationCompat
                .Builder(context, AIRING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_airing)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build(),
        )
    }

    /**
     * Creating a channel that already exists is a documented no-op, so this is called per
     * notification rather than once at startup. That is deliberate: an alert can fire in a process
     * WorkManager started just for it.
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AIRING_CHANNEL_ID,
                context.getString(R.string.alerts_channel_airing_name),
                // DEFAULT, not HIGH: an episode airing in six hours is not an interruption. HIGH
                // would make it heads-up and buzz the phone for something with hours of slack.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.alerts_channel_airing_description) },
        )
    }
}
