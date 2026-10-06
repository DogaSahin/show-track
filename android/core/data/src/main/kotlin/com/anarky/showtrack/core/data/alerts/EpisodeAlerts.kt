package com.anarky.showtrack.core.data.alerts

/**
 * Episode alerts are scheduled on the phone, from the air dates the library already carries. This
 * is the one thing :core:data needs from that: a way to say "the library changed, look again" and
 * "the account is gone, cancel everything". The implementation (WorkManager) lives with the
 * Profile screen's alerts switch; this module never sees it.
 *
 * Both must never throw: an alert is a convenience, and nothing the user is doing should fail
 * because one could not be scheduled.
 */
interface EpisodeAlerts {
    /** Re-plan alerts soon (after sign-in, or after a title was added, removed or changed status). */
    suspend fun requestSync()

    /** Cancel every scheduled alert and forget which ones already fired (sign-out). */
    suspend fun cancelAll()
}
