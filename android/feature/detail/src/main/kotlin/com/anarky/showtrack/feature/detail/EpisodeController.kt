package com.anarky.showtrack.feature.detail

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.LibraryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Episodes section's behaviour, kept out of DetailViewModel so that class stays about the
 * title. It reads the one screen state through [read] and writes it through [update], which
 * ignores anything but a loaded screen.
 *
 * Every change is optimistic: the circles move at once and one `PUT` goes out per action (a tap, a
 * catch-up range, a season). A failed request puts back exactly the episodes it was about and
 * reports [DetailActionError.Edit]; a successful one brings the recounted progress.
 */
@Suppress("TooManyFunctions") // one function per user action, as in the screen ViewModels
internal class EpisodeController(
    private val scope: CoroutineScope,
    private val mediaId: String,
    private val mediaRepository: MediaRepository,
    private val libraryRepository: LibraryRepository,
    private val read: () -> DetailUiState.Success?,
    private val update: ((DetailUiState.Success) -> DetailUiState.Success) -> Unit,
) {
    private var loadJob: Job? = null

    // Saves go out one at a time, so their answers land in the order they were made and the
    // progress on screen is always the latest one.
    private val saveLock = Mutex()

    @Suppress("TooGenericExceptionCaught")
    fun load(entry: LibraryEntry?) {
        loadJob?.cancel()
        update { it.copy(episodes = EpisodesState.Loading) }
        loadJob =
            scope.launch {
                val loaded =
                    try {
                        val list = mediaRepository.episodes(mediaId)
                        if (!list.isAvailable) {
                            EpisodesState.NotAvailable
                        } else {
                            val fetched = entry?.let { libraryRepository.watchedEpisodes(it.id) }.orEmpty()
                            // The entry may have changed while this loaded (an add or a remove), and
                            // that action's onAdded/onRemoved had no list to update yet: take the
                            // screen's entry as it is now.
                            val current = read()?.data?.entry
                            val watched = if (current != null && current.id == entry?.id) fetched else emptySet()
                            EpisodesState.Ready(
                                list = list,
                                watched = watched,
                                tracking = current != null,
                                expanded = EpisodeRules.defaultExpanded(list, watched, tracking = current != null),
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        EpisodesState.Failed
                    }
                update { it.copy(episodes = loaded) }
            }
    }

    fun toggleSeason(number: Int) =
        updateReady { ready ->
            ready.copy(expanded = if (number in ready.expanded) ready.expanded - number else ready.expanded + number)
        }

    /** One episode. Marking one with aired, unwatched episodes before it offers to catch those up. */
    fun toggle(episode: Episode) {
        val ready = ready() ?: return
        if (!ready.tracking || !episode.aired || busy()) return
        val marking = episode.id !in ready.watched
        val watched = if (marking) ready.watched + episode.id else ready.watched - episode.id
        val catchUp = if (marking) EpisodeRules.catchUpFor(ready.list, watched, episode) else null
        updateReady { it.copy(watched = watched, catchUp = catchUp) }
        save(setOf(episode.id), watched = marking)
    }

    /** Acts on [shown], the prompt the user answered, and only while it is still the current one. */
    fun acceptCatchUp(shown: CatchUp) {
        if (ready()?.catchUp != shown) return
        if (busy()) {
            // The snackbar is gone either way: never leave its highlight behind.
            dismissCatchUp(shown)
            return
        }
        updateReady { it.copy(watched = it.watched + shown.episodeIds, catchUp = null) }
        save(shown.episodeIds, watched = true)
    }

    fun dismissCatchUp(shown: CatchUp) = updateReady { if (it.catchUp == shown) it.copy(catchUp = null) else it }

    /** "Mark season watched / unwatched": aired episodes only. */
    fun markSeason(
        number: Int,
        watched: Boolean,
    ) {
        val ready = ready() ?: return
        if (!ready.tracking || busy()) return
        val season = ready.list.seasons.firstOrNull { it.number == number } ?: return
        val ids = EpisodeRules.airedIds(season)
        val changing = if (watched) ids - ready.watched else ids intersect ready.watched
        if (changing.isEmpty()) return
        updateReady {
            it.copy(watched = if (watched) it.watched + changing else it.watched - changing, catchUp = null)
        }
        save(changing, watched)
    }

    /**
     * Just added: load again for the entry's watched episodes. Usually none, but adding is
     * idempotent, so the title may already have been tracked (another device, an older screen).
     */
    fun onAdded(entry: LibraryEntry) = load(entry)

    /** Just removed: back to seasons without circles. A load in flight reads the entry when it lands. */
    fun onRemoved() = updateReady { it.copy(tracking = false, watched = emptySet(), catchUp = null) }

    @Suppress("TooGenericExceptionCaught")
    private fun save(
        ids: Set<String>,
        watched: Boolean,
    ) {
        val entryId = read()?.data?.entry?.id ?: return
        scope.launch {
            try {
                val updated = saveLock.withLock { libraryRepository.setWatched(entryId, ids, watched) }
                // Only onto the same entry: one removed meanwhile must not come back.
                update {
                    if (it.data.entry?.id != entryId) {
                        it
                    } else {
                        it.copy(
                            data = it.data.copy(entry = updated),
                            actionError = it.actionError.takeUnless { error -> error is DetailActionError.Edit },
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Undo exactly this request's episodes; anything tapped since stays as it is. A
                // catch-up prompt built on the failed tap goes too.
                updateReady { it.copy(watched = if (watched) it.watched - ids else it.watched + ids, catchUp = null) }
                update { it.copy(actionError = DetailActionError.Edit(failure)) }
            }
        }
    }

    private fun ready(): EpisodesState.Ready? = read()?.episodes as? EpisodesState.Ready

    /** An add or a remove is running: episode actions wait for the entry to settle. */
    private fun busy(): Boolean = read()?.changingEntry == true

    private fun updateReady(transform: (EpisodesState.Ready) -> EpisodesState.Ready) =
        update { success ->
            val ready = success.episodes as? EpisodesState.Ready
            if (ready == null) success else success.copy(episodes = transform(ready))
        }
}
