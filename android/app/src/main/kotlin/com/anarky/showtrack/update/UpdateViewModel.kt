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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * [progress] is 0..1 while a download runs and null otherwise. [notVerified] narrows [failed]: the
 * file downloaded but is not a ShowTrack build signed with this app's key, so it was deleted rather
 * than installed. [explainPermission] shows the "allow ShowTrack to install updates" explanation
 * before the settings trip. [signedOut] flips once the blocking screen's Sign out has ended the
 * session, so the host can navigate to sign-in.
 */
data class UpdateUi(
    val state: UpdateState = UpdateState.Unknown,
    val progress: Float? = null,
    val failed: Boolean = false,
    val notVerified: Boolean = false,
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
 * signed-in session rather than once per composition — plus again on a resume once the last answer
 * is [RECHECK_AFTER] old, so a phone left open for days still learns about a new minimum.
 *
 * `TooManyFunctions` is suppressed: eight of the functions are [UpdateActions], and the rest are
 * the steps of one install flow that read better as named steps than as one long function.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val repository: AppUpdateRepository,
        private val installer: ApkInstaller,
        private val auth: AuthRepository,
        private val clock: Clock,
    ) : ViewModel(),
        UpdateActions {
        private val mutableUi = MutableStateFlow(UpdateUi())
        val ui: StateFlow<UpdateUi> = mutableUi.asStateFlow()

        private var checkStarted = false
        private var checking: Job? = null

        /** When the last check answered; null until the session's first one has. */
        private var lastCheckedAt: Instant? = null

        /** The download, or a retry's re-check and download. */
        private var download: Job? = null

        /** Set by [confirmPermission]; [resumed] continues the install once, then clears it. */
        private var pendingInstall = false

        /**
         * Set when a download fails for any reason: the release it was for may be what is wrong
         * (replaced on the server, a size that no longer matches), so the next Install asks the
         * server again and downloads whatever it offers now.
         */
        private var recheckBeforeDownload = false

        /**
         * APKs already downloaded and verified, by `versionCode` and hash, so cancelling the system
         * installer and tapping Install again re-offers the same file instead of downloading it
         * again — and a release republished under the same `versionCode` is not mistaken for it.
         * Checked with `exists()` before use: the cache can be cleared at any time. Kept across
         * sessions: the file is the same build whoever is signed in.
         */
        private val verified = mutableMapOf<VerifiedKey, File>()

        /** Asks the server once per session. `LaunchedEffect` re-fires on recreation; this does not. */
        override fun check() {
            if (checkStarted) return
            checkStarted = true
            refresh()
        }

        private fun refresh() {
            checking =
                viewModelScope.launch {
                    val state = repository.check()
                    lastCheckedAt = clock.instant()
                    // A re-check that could not reach the server leaves what the last one said.
                    if (state != UpdateState.Unknown || mutableUi.value.state == UpdateState.Unknown) {
                        mutableUi.update { it.copy(state = state) }
                    }
                }
        }

        override fun install() {
            if (download?.isActive == true) return
            val release = mutableUi.value.state.offeredRelease() ?: return
            if (!installer.canInstall()) {
                mutableUi.update { it.copy(explainPermission = true) }
                return
            }
            val cached = verified[VerifiedKey.of(release)]?.takeIf(File::exists)
            mutableUi.update {
                it.copy(progress = if (cached == null) 0f else null, failed = false, notVerified = false)
            }
            download =
                viewModelScope.launch {
                    if (cached != null) {
                        handOffIfTrusted(cached, release)
                    } else {
                        freshRelease(release)?.let { downloadAndInstall(it) }
                    }
                }
        }

        /**
         * [release], or after a failed download the release the server offers now — which may be
         * none (already up to date), or unknown (offline: shown as another failure, Retry stays).
         */
        private suspend fun freshRelease(release: AppRelease): AppRelease? {
            if (!recheckBeforeDownload) return release
            val state = repository.check()
            lastCheckedAt = clock.instant()
            if (state == UpdateState.Unknown) {
                mutableUi.update { it.copy(progress = null, failed = true) }
                return null
            }
            recheckBeforeDownload = false
            val fresh = state.offeredRelease()
            mutableUi.update { it.copy(state = state, progress = if (fresh == null) null else 0f) }
            return fresh
        }

        // A settings screen that cannot open is a failure line with Retry, not a crash.
        @Suppress("TooGenericExceptionCaught")
        override fun confirmPermission() {
            mutableUi.update { it.copy(explainPermission = false) }
            pendingInstall = true
            try {
                installer.openInstallPermissionSettings()
            } catch (_: RuntimeException) {
                pendingInstall = false
                mutableUi.update { it.copy(failed = true) }
            }
        }

        override fun dismissPermission() {
            mutableUi.update { it.copy(explainPermission = false) }
        }

        /**
         * Back from settings: continue only if the permission was granted; otherwise just forget it.
         * Any resume also re-checks once the last answer is stale — but never over a download.
         */
        override fun resumed() {
            if (pendingInstall) {
                pendingInstall = false
                if (installer.canInstall()) install()
            }
            val checkedAt = lastCheckedAt ?: return
            if (!checkStarted || checking?.isActive == true || download?.isActive == true) return
            if (Duration.between(checkedAt, clock.instant()) >= RECHECK_AFTER) refresh()
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
                mutableUi.update {
                    it.copy(state = UpdateState.UpToDate, progress = null, failed = false, notVerified = false)
                }
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
            checking?.cancel()
            checking = null
            lastCheckedAt = null
            pendingInstall = false
            checkStarted = false
            recheckBeforeDownload = false
            mutableUi.value = UpdateUi()
        }

        // The download can fail as an IOException (size/hash mismatch, disk), an HttpException
        // (the APK is gone or the session expired) or anything else; every one of them is a
        // "Download failed" with Retry, never a crash. Cancellation still propagates.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun downloadAndInstall(release: AppRelease) {
            val apk =
                try {
                    // onProgress arrives on Dispatchers.IO; a StateFlow update is safe from any thread.
                    repository.download(release) { fraction -> mutableUi.update { it.copy(progress = fraction) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recheckBeforeDownload = true
                    mutableUi.update { it.copy(progress = null, failed = true) }
                    return
                }
            mutableUi.update { it.copy(progress = null) }
            handOffIfTrusted(apk, release)
        }

        /**
         * Only a build of this app signed with its key reaches the installer. Anything else is
         * deleted, forgotten and reported — and, like a failed download, re-checked before the
         * next attempt.
         */
        private suspend fun handOffIfTrusted(
            apk: File,
            release: AppRelease,
        ) {
            if (!installer.isTrusted(apk)) {
                apk.delete()
                verified.remove(VerifiedKey.of(release))
                recheckBeforeDownload = true
                mutableUi.update { it.copy(progress = null, failed = true, notVerified = true) }
                return
            }
            verified[VerifiedKey.of(release)] = apk
            handOff(apk)
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

        companion object {
            /** How old the last check may be before a resume asks the server again. */
            val RECHECK_AFTER: Duration = Duration.ofHours(6)
        }
    }

/** What a verified download is filed under: the same `versionCode` with a new hash is a new file. */
private data class VerifiedKey(
    val versionCode: Int,
    val sha256: String,
) {
    companion object {
        fun of(release: AppRelease) = VerifiedKey(release.versionCode, release.sha256.lowercase())
    }
}

/** The release an Install tap is for, if the state offers one. */
private fun UpdateState.offeredRelease(): AppRelease? =
    when (this) {
        is UpdateState.Available -> release
        is UpdateState.Required -> release
        UpdateState.UpToDate, UpdateState.Unknown -> null
    }
