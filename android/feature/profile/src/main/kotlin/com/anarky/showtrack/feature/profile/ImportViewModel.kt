package com.anarky.showtrack.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.ImportFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The constructor names one interface from `:core:data` and nothing else — architecture rule 2,
 * the same shape `AuthViewModel`/`LibraryViewModel` have. No use-case layer between this and the
 * repository (owner's standing guidance): a use case per method would be one class forwarding a
 * single call.
 *
 * [state] is a plain `MutableStateFlow`, never `stateIn(SharingStarted...)` — decision C-U: this
 * ViewModel holds no Room-backed upstream, the same reasoning `AuthViewModel.state` follows.
 */
@HiltViewModel
class ImportViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ImportUiState>(ImportUiState.Form())
        val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

        /**
         * `try`/`catch(Exception)`, not `runCatching` — the same reasoning `AuthViewModel.submit`
         * documents: `runCatching` also swallows [CancellationException], which is structured
         * concurrency's own control flow, not a failure this screen should report.
         *
         * Safe to call again after a [ImportUiState.Success] (task brief's "re-running reports
         * everything skipped and changes nothing" acceptance criterion) — nothing here refuses a
         * second call, and [LibraryRepository.importAniList] is what the backend guarantees is
         * idempotent, not this function.
         */
        @Suppress("TooGenericExceptionCaught")
        fun import(username: String) {
            mutableState.value = ImportUiState.Form(submitting = true)
            viewModelScope.launch {
                try {
                    val summary = repository.importAniList(username)
                    mutableState.value = ImportUiState.Success(summary)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.value = ImportUiState.Form(error = failure.toImportError())
                }
            }
        }
    }

private fun Throwable.toImportError(): ImportError =
    when (this) {
        is ImportFailure.ListNotPublic -> ImportError.ProfileNotPublic
        is ImportFailure.InvalidUsername -> ImportError.InvalidUsername
        is ImportFailure.UpstreamUnavailable -> ImportError.UpstreamUnavailable
        is ImportFailure.Offline -> ImportError.Offline
        else -> ImportError.Unknown
    }
