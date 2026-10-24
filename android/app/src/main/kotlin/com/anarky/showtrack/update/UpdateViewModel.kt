package com.anarky.showtrack.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.io.IOException
import javax.inject.Inject

/** [progress] is 0..1 while a download runs and null otherwise. */
data class UpdateUi(
    val state: UpdateState = UpdateState.Unknown,
    val progress: Float? = null,
    val failed: Boolean = false,
)

/**
 * Drives the update banner and the blocking screen. Activity-scoped (resolved in `ShowTrackApp`
 * above any back-stack entry), so a download survives rotation and the check runs once per
 * process rather than once per composition.
 */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val repository: AppUpdateRepository,
        private val installer: ApkInstaller,
    ) : ViewModel() {
        private val mutableUi = MutableStateFlow(UpdateUi())
        val ui: StateFlow<UpdateUi> = mutableUi.asStateFlow()

        /** One stable instance for [UpdatePrompts], so recomposition never sees new lambdas. */
        val actions = UpdateActions(onCheck = ::check, onInstall = ::install, onLater = ::later)

        private var checkStarted = false
        private var download: Job? = null

        /** Asks the server once. `LaunchedEffect` re-fires on recreation; this ViewModel does not. */
        fun check() {
            if (checkStarted) return
            checkStarted = true
            viewModelScope.launch {
                val state = repository.check()
                mutableUi.update { it.copy(state = state) }
            }
        }

        fun install() {
            if (download?.isActive == true) return
            val release = offeredRelease() ?: return
            if (!installer.canInstall()) {
                // The user comes back from settings and taps Install again; there is no result
                // callback for this screen, so nothing to resume automatically.
                installer.openInstallPermissionSettings()
                return
            }
            mutableUi.update { it.copy(progress = 0f, failed = false) }
            download = viewModelScope.launch { downloadAndInstall(release) }
        }

        /** Puts off an optional update for a day. A required one has no Later. */
        fun later() {
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

        // The download can fail as an IOException (size/hash mismatch, disk), an HttpException
        // (the APK is gone or the session expired) or anything else; every one of them is a
        // "Download failed" with Retry, never a crash. Cancellation still propagates.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun downloadAndInstall(release: AppRelease) {
            try {
                // onProgress arrives on Dispatchers.IO; a StateFlow update is safe from any thread.
                val apk = repository.download(release) { fraction -> mutableUi.update { it.copy(progress = fraction) } }
                mutableUi.update { it.copy(progress = null) }
                installer.install(apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(progress = null, failed = true) }
            }
        }

        private fun offeredRelease(): AppRelease? =
            when (val state = mutableUi.value.state) {
                is UpdateState.Available -> state.release
                is UpdateState.Required -> state.release
                UpdateState.UpToDate, UpdateState.Unknown -> null
            }
    }
