package com.anarky.showtrack.core.data.update

import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState
import java.io.File

interface AppUpdateRepository {
    /** Asks the server; never throws. A failed ask is [UpdateState.Unknown], never `Required`. */
    suspend fun check(): UpdateState

    /**
     * Downloads [release] into the cache, reporting 0..1, and returns the file once its size and
     * SHA-256 match. A mismatch deletes the file and throws `IOException`.
     */
    suspend fun download(
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ): File

    /** Hides [release] from `Available` for 24 hours. Has no effect on `Required`. */
    suspend fun snooze(release: AppRelease)
}
