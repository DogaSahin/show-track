package com.anarky.showtrack.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The first real consumer of the app graph, and the whole point of it: the constructor names an
 * INTERFACE from `:core:data` and nothing else. Retrofit, Room, the DTOs and the entities are all
 * `implementation`-scoped inside that module, so this module could not name them if it tried —
 * architecture rule 2 enforced by the compile classpath rather than by review.
 *
 * No use-case layer between this and the repository (owner's standing guidance): a use case per
 * method would be one class each forwarding a single call.
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val mutableLastError = MutableStateFlow<Throwable?>(null)

        /**
         * `stateIn` rather than exposing the cold flow: the repository's flow reads Room and the
         * paginator on every collection, so a configuration change would restart it and the list
         * would blink. `WhileSubscribed(5_000)` keeps the upstream alive across exactly that gap
         * and then stops it, so a backgrounded screen is not holding a Room observer open.
         *
         * The 5s is not folklore — it is longer than an activity recreation and shorter than any
         * plausible "user came back" delay, which is the window this operator exists to bridge.
         */
        val entries: StateFlow<List<LibraryEntry>> =
            repository
                .observeLibrary()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                    initialValue = emptyList(),
                )

        /**
         * The last failure from [refresh] or [loadMore], or null. It exists because the
         * alternative is worse, not because the UI consumes it yet: both repository calls do
         * network I/O, and an exception escaping a `viewModelScope.launch` is an uncaught
         * throwable on the main dispatcher — i.e. a crash. Rendering it is the library feature's
         * job; not swallowing it is this class's.
         */
        val lastError: StateFlow<Throwable?> = mutableLastError.asStateFlow()

        fun refresh() = guard { repository.refresh() }

        fun loadMore() = guard { repository.loadMore() }

        /**
         * `try`/`catch(Exception)` and not `runCatching`: runCatching swallows
         * [CancellationException] as well, which is structured concurrency's own control flow —
         * a cancelled child that eats its cancellation stops the parent from ever completing.
         * Catching Exception rather than Throwable leaves Errors (OOM, StackOverflow) alone,
         * which are not something a screen can recover from.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun guard(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                    mutableLastError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableLastError.value = failure
                }
            }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
