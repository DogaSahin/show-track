package com.anarky.showtrack.core.data.session

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowTrackSession"

/**
 * Something that holds one account's data on the phone: a cache, an in-memory list, a remembered
 * choice. Each one forgets it in [clearUserData], so the next account to sign in on the phone
 * sees none of it, even offline.
 */
interface UserData {
    suspend fun clearUserData()
}

/**
 * Clears every [UserData] holder. Called at sign-out (after the tokens are gone), when a session
 * expires, and at every sign-in — the last so that a sign-out interrupted half way (the process
 * killed) still cannot hand the previous account's data to the next one.
 */
@Singleton
class UserDataCleaner
    @Inject
    constructor(
        private val holders: Set<@JvmSuppressWildcards UserData>,
    ) {
        // Each holder separately: one failing must not leave the others' data behind.
        @Suppress("TooGenericExceptionCaught")
        suspend fun clear() {
            holders.forEach { holder ->
                try {
                    holder.clearUserData()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Log.w(TAG, "clearing ${holder.javaClass.simpleName} failed: ${failure.javaClass.simpleName}")
                }
            }
        }
    }

/**
 * Drops writes that began under a session that has since ended. A request still in flight at
 * sign-out would otherwise land afterwards and put the old account's rows back: capture [current]
 * before the request, and write through [ifStill].
 */
class SessionGuard {
    private val lock = Mutex()
    private var session = 0L

    suspend fun current(): Long = lock.withLock { session }

    /** Runs [write] only if no session ended since [startedIn]. */
    suspend fun ifStill(
        startedIn: Long,
        write: suspend () -> Unit,
    ) {
        lock.withLock { if (startedIn == session) write() }
    }

    /** Ends the session: [clear] runs before any write that began under it can land. */
    suspend fun end(clear: suspend () -> Unit) {
        lock.withLock {
            session++
            clear()
        }
    }
}
