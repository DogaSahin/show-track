package com.anarky.showtrack.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.navigation.DetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * One title, with or without a library entry (decision C-D). Reachable three ways — a library
 * row (an entry is guaranteed), a search result, and a push deep-link (neither guarantees one) —
 * so `entry == null` is read here as "not in your library", never folded into [DetailUiState.Error].
 *
 * **Three operations, three blast radii — carried forward from task 9a.8's shipped bug.** A
 * single shared error slot let a failed page-2 fetch there discard a fully-populated screen; the
 * same mistake here would be worse, because this screen's "page 2" is a mutation the user just
 * asked for. So:
 * - The initial [load] (and [retry]) is the ONLY thing that may replace [state] with
 *   [DetailUiState.Error]. It runs before there is anything to show, so a full-screen outcome
 *   costs the user nothing they had.
 * - [addToLibrary] and every edit ([setScore], [clearScore], [setProgress], [setStatus],
 *   [toggleFavorite]) never touch [DetailUiState.Error]. A failure there is written into
 *   [DetailUiState.Success.actionError] — tagged [DetailActionError.Add] or [DetailActionError.Edit]
 *   so the screen renders it beside the control that actually failed — while [DetailUiState.Success.data]
 *   is left exactly as it was. The title stays fully on screen throughout.
 *
 * **No optimistic updates.** [DetailUiState.Success.data]'s `entry` is only ever replaced by what
 * [LibraryRepository.add] or [LibraryRepository.update] actually returns — never by the value the
 * user tapped. That is what makes a failed edit's "restore the previous value" free: nothing was
 * changed on the way in, so there is nothing to undo on the way out. It is also why the state the
 * server hands back — not the request — is what callers see (the server owns `updated_at` and may
 * clamp a value).
 *
 * **`state` is a plain [MutableStateFlow], not `combine(...).stateIn(WhileSubscribed(5_000))`
 * the way `LibraryViewModel.state` is.** `WhileSubscribed` exists there to stop a live
 * Room-backed [kotlinx.coroutines.flow.Flow] from being re-collected — and its query re-run — for
 * a screen nobody is watching. Nothing here is a continuously updating upstream flow:
 * [MediaRepository.detail], [LibraryRepository.entryForMedia], [LibraryRepository.add] and
 * [LibraryRepository.update] are all one-shot suspend calls this ViewModel drives itself, so
 * there is no expensive subscription to gate — and [asStateFlow] means a collector (a test
 * reading `.value` synchronously included) always sees the latest emission without first having
 * to keep something subscribed to advance past `initialValue`.
 */
@HiltViewModel
class DetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val mediaRepository: MediaRepository,
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        private val mediaId: String = savedStateHandle.toRoute<DetailRoute>().mediaId

        private val mutableState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
        val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

        init {
            load()
        }

        /** Re-runs the initial load — the only operation allowed to show [DetailUiState.Error]. */
        fun retry() = load()

        /**
         * `entry == null` only, in practice (the screen shows this action exactly then), but
         * nothing here asserts that — a retry after a transient failure calls it again with the
         * same intent, and the endpoint is idempotent either way (decision, carried forward from
         * 9a.5's review).
         */
        @Suppress("TooGenericExceptionCaught")
        fun addToLibrary() {
            val current = mutableState.value as? DetailUiState.Success ?: return
            if (current.saving) return
            mutableState.value = current.copy(saving = true, actionError = null)
            viewModelScope.launch {
                try {
                    val entry =
                        libraryRepository.add(
                            source = current.data.media.source,
                            externalId = current.data.media.externalId,
                        )
                    replaceSuccess { it.copy(data = it.data.copy(entry = entry), saving = false, actionError = null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Could be a failed POST, or a successful POST followed by a failed post-add
                    // refresh() (LibraryRepositoryImpl.add's documented wrinkle) — either way the
                    // title may already be in the library, so the copy this maps to must not
                    // claim the add failed outright. A retry is always safe: the endpoint is
                    // idempotent.
                    replaceSuccess { it.copy(saving = false, actionError = DetailActionError.Add(failure)) }
                }
            }
        }

        /** Sends only the score — see the class KDoc on why every edit here names one field. */
        fun setScore(score: BigDecimal) = edit(LibraryPatch(score = ScoreChange.Set(score)))

        /** Score's third wire state: absent means "leave it", this means "unrate it". */
        fun clearScore() = edit(LibraryPatch(score = ScoreChange.Clear))

        fun setProgress(progress: Int) = edit(LibraryPatch(progress = progress))

        fun setStatus(status: UserMediaStatus) = edit(LibraryPatch(status = status))

        fun toggleFavorite() {
            val entry = (mutableState.value as? DetailUiState.Success)?.data?.entry ?: return
            edit(LibraryPatch(favorite = !entry.favorite))
        }

        @Suppress("TooGenericExceptionCaught")
        private fun load() {
            // Set synchronously, before a coroutine is even launched: a retry from
            // DetailUiState.Error must not leave the OLD error on screen for the round trip's
            // whole duration (9a.8's other carried-forward lesson). Replacing the entire state
            // object with Loading — rather than a field flip inside a `combine` — clears it
            // immediately by construction.
            mutableState.value = DetailUiState.Loading
            viewModelScope.launch {
                try {
                    val data =
                        coroutineScope {
                            // Neither call depends on the other; serialising them would double
                            // time-to-first-paint on the deep-link path, which is the one path
                            // where the user is waiting with nothing on screen yet.
                            val mediaDeferred = async { mediaRepository.detail(mediaId) }
                            val entryDeferred = async { libraryRepository.entryForMedia(mediaId) }
                            DetailData(media = mediaDeferred.await(), entry = entryDeferred.await())
                        }
                    mutableState.value = DetailUiState.Success(data = data)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.value = DetailUiState.Error(failure)
                }
            }
        }

        /**
         * Every score/progress/status/favourite edit funnels through here. [entryId] is read from
         * the CURRENT state rather than cached at construction, because it is only known once the
         * initial load has resolved into [DetailUiState.Success] — calling an edit before that (or
         * with no entry at all, which the screen never offers a control for) is a no-op.
         *
         * Re-entrancy is guarded the same way [com.anarky.showtrack.core.data.paging.CursorPaginator]-adjacent
         * `loadMore` is guarded on the library screen: a second tap while [DetailUiState.Success.saving]
         * is already true is dropped rather than queued, since `saving` is what disables the
         * controls in the first place — this is the belt to that UI's braces.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun edit(patch: LibraryPatch) {
            val current = mutableState.value as? DetailUiState.Success ?: return
            val entryId = current.data.entry?.id ?: return
            if (current.saving) return
            mutableState.value = current.copy(saving = true, actionError = null)
            viewModelScope.launch {
                try {
                    val updated = libraryRepository.update(entryId, patch)
                    replaceSuccess { it.copy(data = it.data.copy(entry = updated), saving = false, actionError = null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // data is left untouched: it was never optimistically changed, so there is
                    // nothing to revert — see the class KDoc.
                    replaceSuccess { it.copy(saving = false, actionError = DetailActionError.Edit(failure)) }
                }
            }
        }

        private inline fun replaceSuccess(transform: (DetailUiState.Success) -> DetailUiState.Success) {
            val latest = mutableState.value as? DetailUiState.Success ?: return
            mutableState.value = transform(latest)
        }
    }
