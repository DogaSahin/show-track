package com.anarky.showtrack.feature.profile.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.anarky.showtrack.core.model.PushNotification
import com.anarky.showtrack.core.navigation.detailDeepLink
import com.anarky.showtrack.feature.profile.R

/** The channel every airing notification goes to. Public so a settings screen can deep-link it. */
const val AIRING_CHANNEL_ID: String = "showtrack_airing"

/**
 * Turns a decoded [PushNotification] into a system notification whose tap opens the title.
 *
 * Separate from [ShowTrackMessagingReceiver] because the receiver cannot be constructed by a
 * test — the system instantiates it — while this can be handed a Robolectric `Context` and
 * driven directly. That is what makes [deepLinkIntent] assertable rather than merely readable,
 * and the deep link is the half of this feature that fails silently when it is wrong: a bad URI
 * matches no destination, the tap opens the app's start screen, and nothing anywhere reports it.
 */
object PushNotifier {
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

    fun show(
        context: Context,
        notification: PushNotification,
    ) {
        // Checked rather than assumed, and NOT because the notification would throw — it would
        // not. On API 33+ a post without the runtime permission is silently DROPPED, which
        // presents as "push is broken" with nothing in logcat. Returning early at least leaves
        // the profile screen's prompt as the single explanation of why nothing arrived.
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
                notification.mediaId.hashCode(),
                deepLinkIntent(context, notification.mediaId),
                // IMMUTABLE is mandatory on API 31+ and correct everywhere: a mutable
                // PendingIntent handed to the system notification shade is a capability another
                // process can rewrite. UPDATE_CURRENT so a re-delivered notification for the same
                // title refreshes rather than resurrecting a stale extra.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        NotificationManagerCompat.from(context).notify(
            notification.mediaId.hashCode(),
            NotificationCompat
                .Builder(context, AIRING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_airing)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build(),
        )
    }

    /**
     * Creating a channel that already exists is a documented no-op, so this is called per
     * notification rather than once at startup. That is deliberate: a broadcast can arrive in a
     * process that was started FOR the broadcast, where no Application-level init has run.
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AIRING_CHANNEL_ID,
                context.getString(R.string.push_channel_airing_name),
                // DEFAULT, not HIGH: an episode airing in six hours is not an interruption. HIGH
                // would make it heads-up and buzz the phone for something with hours of slack.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.push_channel_airing_description) },
        )
    }
}
