package com.anarky.showtrack.core.model

/** The newest app build the server offers. [sha256] is lowercase hex of the APK bytes. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val notes: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** What the installed build knows about updates. [Unknown] means the server could not be asked. */
sealed interface UpdateState {
    data object UpToDate : UpdateState

    data object Unknown : UpdateState

    data class Available(
        val release: AppRelease,
    ) : UpdateState

    /** The installed build is below the server's minimum; cannot be snoozed. */
    data class Required(
        val release: AppRelease,
    ) : UpdateState
}
