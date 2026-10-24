package com.anarky.showtrack.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.update.AppUpdateRepository
import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * [progress] is 0..1 while a download runs and null otherwise. [explainPermission] shows the
 * "allow ShowTrack to install updates" explanation before the settings trip. [signedOut] flips once
 * the blocking screen's Sign out has ended the session, so the host can navigate to sign-in.
 */
data class UpdateUi(
    val state: UpdateState = UpdateState.Unknown,
    val progress: Float? = null,
    val failed: Boolean = false,
    val explainPermission: Boolean = false,
    val signOutFailed: Boolean = false,
    val signedOut: Boolean = false,
)

/** What [UpdatePrompts] calls back into. [UpdateViewModel] is the only production implementation. */
interface UpdateActions {
    fun check()

    fun install()

    fun later()

    /** The explanation's "Open settings": opens the setting and remembers to resume the install. */
    fun confirmPermission()

    fun dismissPermission()

    /** The screen came back to the foreground, e.g. from the "Install unknown apps" setting. */
    fun resumed()

    fun signOut()

    /** The session is over (the sign-in screen is showing): forget this session's update state. */
    fun sessionEnded()
}

/**
 * Drives the update banner and the blocking screen. Activity-scoped (resolved in `ShowTrackApp`
 * above any back-stack entry), so a download survives rotation and the check runs once per
 * signed-in session rather than once per composition.
 */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val repository: AppUpdateRepository,
        private val installer: ApkInstaller,
        private val auth: AuthRepository,
    ) : ViewModel(),
        UpdateActions {
        private val mutableUi = MutableStateFlow(UpdateUi())
        val ui: StateFlow<UpdateUi> = mutableUi.asStateFlow()

        private var checkStarted = false
        private var download: Job? = null

        /** Set by [confirmPermission]; [resumed] continues the install once, then clears it. */
        private var pendingInstall = false

        /**
         * APKs already downloaded and verified, by `versionCode`, so cancelling the system
         * installer and tapping Install again re-offers the same file instead of downloading it
         * again. Checked with `exists()` before use: the cache can be cleared at any time. Kept
         * across sessions: the file is the same build whoever is signed in.
         */
        private val verified = mutableMapOf<Int, File>()

        /** Asks the server once per session. `LaunchedEffect` re-fires on recreation; this does not. */
        override fun check() {
            if (checkStarted) return
            checkStarted = true
            viewModelScope.launch {
                val state = repository.check()
                mutableUi.update { it.copy(state = state) }
            }
        }

        override fun install() {
            if (download?.isActive == true) return
            val release = mutableUi.value.state.offeredRelease() ?: return
            if (!installer.canInstall()) {
                mutableUi.update { it.copy(explainPermission = true) }
                return
            }
            val apk = verified[release.versionCode]?.takeIf(File::exists)
            if (apk != null) {
                mutableUi.update { it.copy(failed = false) }
                handOff(apk)
                return
            }
            mutableUi.update { it.copy(progress = 0f, failed = false) }
            download = viewModelScope.launch { downloadAndInstall(release) }
        }

        override fun confirmPermission() {
            mutableUi.update { it.copy(explainPermission = false) }
            pendingInstall = true
            installer.openInstallPermissionSettings()
        }

        override fun dismissPermission() {
            mutableUi.update { it.copy(explainPermission = false) }
        }

        /** Back from settings: continue only if the permission was granted; otherwise just forget it. */
        override fun resumed() {
            if (!pendingInstall) return
            pendingInstall = false
            if (installer.canInstall()) install()
        }

        /** Puts off an optional update for a day. A required one has no Later. */
        override fun later() {
            val release = (mutableUi.value.state as? UpdateState.Available)?.release ?: return
            viewModelScope.launch {
                try {
                    repository.snooze(release)
                } catch (_: IOException) {
                    // The snooze did not persist, so the banner returns next launch; still hide it now.
                }
                mutableUi.update { it.copy(state = UpdateState.UpToDate, progress = null, failed = false) }
            }
        }

        /**
         * The blocking screen's way out — the same `AuthRepository.logout()` Profile's Sign out
         * calls. `logout()` emits no `AuthEvent` (that is reserved for a failed token refresh), so
         * [UpdateUi.signedOut] is what tells the host to navigate to sign-in, which in turn
         * reaches [sessionEnded].
         */
        @Suppress("TooGenericExceptionCaught")
        override fun signOut() {
            mutableUi.update { it.copy(signOutFailed = false) }
            viewModelScope.launch {
                try {
                    auth.logout()
                    mutableUi.update { it.copy(signedOut = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableUi.update { it.copy(signOutFailed = true) }
                }
            }
        }

        override fun sessionEnded() {
            download?.cancel()
            download = null
            pendingInstall = false
            checkStarted = false
            mutableUi.value = UpdateUi()
        }

        // The download can fail as an IOException (size/hash mismatch, disk), an HttpException
        // (the APK is gone or the session expired) or anything else; every one of them is a
        // "Download failed" with Retry, never a crash. Cancellation still propagates.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun downloadAndInstall(release: AppRelease) {
            try {
                // onProgress arrives on Dispatchers.IO; a StateFlow update is safe from any thread.
                val apk = repository.download(release) { fraction -> mutableUi.update { it.copy(progress = fraction) } }
                verified[release.versionCode] = apk
                mutableUi.update { it.copy(progress = null) }
                handOff(apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(progress = null, failed = true) }
            }
        }

        // An installer that cannot start (no activity, a revoked grant) is a "Download failed"
        // with Retry too, rather than a crash on the main thread.
        @Suppress("TooGenericExceptionCaught")
        private fun handOff(apk: File) {
            try {
                installer.install(apk)
            } catch (_: RuntimeException) {
                mutableUi.update { it.copy(failed = true) }
            }
        }
    }

/** The release an Install tap is for, if the state offers one. */
private fun UpdateState.offeredRelease(): AppRelease? =
    when (this) {
        is UpdateState.Available -> release
        is UpdateState.Required -> release
        UpdateState.UpToDate, UpdateState.Unknown -> null
    }
