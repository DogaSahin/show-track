package com.anarky.showtrack.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
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
 *
 * **The group section (task 9c.6) is a THIRD independent subsystem, alongside load and edit.**
 * [groupId] never comes from `SavedStateHandle`/`DetailRoute` — the ruling that resolved this
 * (`progress.md`) deliberately did not widen `DetailRoute` to carry it, since that would touch five
 * existing call sites (Library, Search, Discover, Favorites, Feed) for no gain when a plain
 * parameter says the same thing. `DetailScreen`'s stateful wrapper reads the active group from a
 * `StateFlow<ActiveGroupState>` handed in from `:app` and calls [setActiveGroup] with whatever it
 * currently resolves to — `FeedScreen`'s `viewModel.selectGroup(...)`/`LifecycleResumeEffect`
 * mechanism transplanted here.
 *
 * **The race [setActiveGroup]/[load] genuinely have to share: which one resolves to
 * [DetailUiState.Success] first is not fixed.** Both are independent, parallel fetches launched at
 * roughly the same time from composition — the group section needs only [mediaId] (known from
 * construction) and a group id, not the loaded [DetailData] at all. Folding the group section
 * straight into a `replaceSuccess { it.copy(...) }` call the way an edit does would silently DROP a
 * group-section result that lands before [load] has ever produced a [DetailUiState.Success] to
 * patch — [replaceSuccess] no-ops when the state is not already [DetailUiState.Success]. [groupSection]
 * is the fix: the CANONICAL, always-current value lives in this private field, not only inside
 * whatever [DetailUiState.Success] currently exists. [load]'s own success branch always reads it
 * when constructing a fresh [DetailUiState.Success], and [applyGroupSection] always writes it here
 * FIRST and patches an existing [DetailUiState.Success] second — so regardless of which of the two
 * fetches resolves first, the group section's latest known value is never lost, only ever
 * temporarily unpublished until there is a [DetailUiState.Success] to hold it.
 *
 * `@Suppress("TooManyFunctions")` — [GroupRepository]'s own KDoc gives the identical seam-cohesion
 * argument for why this stays one class rather than being split by sub-concern (load/edit vs. group
 * section vs. propose): every one of these is "what happens when you open this one title," and a
 * caller (`DetailScreen`) has no business knowing which internal subsystem answers which action.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class DetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val mediaRepository: MediaRepository,
        private val libraryRepository: LibraryRepository,
        private val groupRepository: GroupRepository,
    ) : ViewModel() {
        private val mediaId: String = savedStateHandle.toRoute<DetailRoute>().mediaId

        private val mutableState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
        val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

        // The group section's OWN copy of the currently-active group id — independent of
        // DetailRoute (this class's own KDoc) — and its own canonical GroupSectionState, which
        // [applyGroupSection] keeps in sync with whatever DetailUiState.Success.groupSection
        // currently shows. Both start at the values that mean "nothing selected yet".
        private var groupId: String? = null
        private var groupSection: GroupSectionState = GroupSectionState.Absent

        // Bumped every time [setActiveGroup] switches to a genuinely DIFFERENT group id —
        // FeedViewModel.generation's identical shape, one section over: a progress/reviews fetch
        // for group A that is still in flight when the active group switches to B, and lands only
        // afterward, must not overwrite B's already-rendered rows with A's late (or stale-failed)
        // response. Checked immediately before every write [reloadGroupSection] makes.
        private var groupSectionGeneration = 0

        // Which [groupSectionGeneration] currently has a [reloadGroupSection] in flight, or null —
        // FeedViewModel.loadingGeneration's identical shape: guards a resume racing a still-running
        // fetch for the SAME group into a redundant second round trip. Never mistaken for a stuck
        // guard across a switch: [setActiveGroup] bumps [groupSectionGeneration] on every switch,
        // which makes the equality check below false again immediately even though the stored value
        // itself does not change until the NEW fetch's own launch sets it.
        private var loadingGroupSectionGeneration: Int? = null

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

        /**
         * Called by [DetailScreen]'s stateful wrapper with whatever `ActiveGroupState` currently
         * resolves to — on every resume, `FeedViewModel.selectGroup`'s identical call pattern, but
         * unlike that function this one is meaningful for `groupId == null` too: "the account is in
         * no groups" and "the active group has not resolved yet" are both real, renderable states
         * here ([GroupSectionState.Absent]), not a no-op to skip the way Feed's caller skips a null
         * group entirely (that screen has nothing else to show without one; this screen always has
         * the title itself).
         *
         * Two cases, [FeedViewModel.selectGroup]'s identical split:
         * - **The group changed** (including the very first non-null call, [groupId] starting
         *   null): [groupSectionGeneration] is bumped FIRST, so a stale fetch for the OLD group
         *   cannot land after this point and be mistaken for current (`reloadGroupSection`'s own
         *   guards read the generation this bump just changed). [applyGroupSection] then blanks to
         *   [GroupSectionState.Absent] (no group) or [GroupSectionState.Loading] (a new group,
         *   fetch about to launch) UNCONDITIONALLY — the OLD group's rows must never linger on
         *   screen under the NEW group's id, even if they were a fully-loaded
         *   [GroupSectionState.Loaded], `FeedViewModel.selectGroup`'s identical reasoning for why
         *   that blank cannot be conditional on "is there already something loaded".
         * - **The group is unchanged** (an ordinary resume): falls straight through to
         *   [reloadGroupSection], which applies the settled refresh shape — keep whatever is loaded
         *   and mark it stale on failure, rather than blanking.
         */
        fun setActiveGroup(newGroupId: String?) {
            if (newGroupId != groupId) {
                groupId = newGroupId
                groupSectionGeneration++
                applyGroupSection(if (newGroupId == null) GroupSectionState.Absent else GroupSectionState.Loading)
            }
            if (groupId != null) reloadGroupSection()
        }

        /** The group section's own retry button — [reloadGroupSection] applies the same settled refresh shape. */
        fun retryGroupSection() = reloadGroupSection()

        /**
         * "Propose to a group" (task 9c.6, moved here from `:feature:groups` — see
         * [GroupSectionState]'s sibling KDoc on [DetailUiState.Success] and `GroupSection.kt`'s own
         * KDoc for why a search-backed picker could not live there). [groupId] is whichever group
         * the caller chose from the picker, independent of [DetailViewModel]'s own active-group
         * scope — see [DetailUiState.Success.proposing]'s KDoc for why the two are deliberately not
         * the same value.
         *
         * [DetailUiState.Success.proposing]/`.proposeError` are their OWN channel (decision C-S),
         * guarded and cleared exactly like [edit]'s `saving`/`actionError`: a second tap while a
         * propose is already in flight is dropped, and the error is cleared the moment a retry
         * launches, not only on success.
         */
        fun proposeToGroup(groupId: String) {
            val current = mutableState.value as? DetailUiState.Success ?: return
            if (current.proposing) return
            mutableState.value = current.copy(proposing = true, proposeError = null)
            viewModelScope.launch {
                try {
                    groupRepository.proposeTitle(groupId, mediaId)
                    replaceSuccess { it.copy(proposing = false, proposeError = null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    replaceSuccess { it.copy(proposing = false, proposeError = failure.failure) }
                }
            }
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
                    // groupSection = groupSection (not the default GroupSectionState.Absent): the
                    // class KDoc's own race — a group-section fetch that resolved BEFORE this load
                    // did must not be discarded just because there was no DetailUiState.Success to
                    // patch it into yet.
                    mutableState.value = DetailUiState.Success(data = data, groupSection = groupSection)
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

        /**
         * The actual progress/reviews fetch [setActiveGroup] (resume/unchanged-group leg) and
         * [retryGroupSection] both share — `FeedViewModel.refresh`'s identical split from its own
         * `reload`.
         *
         * **Re-entrancy guard first** ([loadingGroupSectionGeneration] == [groupSectionGeneration]):
         * a resume racing an already-in-flight fetch for the SAME group is dropped rather than
         * launching a redundant second round trip — never mistaken for a stuck flag across a group
         * switch, because [setActiveGroup] bumps [groupSectionGeneration] on every switch, which
         * makes this comparison false again immediately.
         *
         * **Blank to [GroupSectionState.Loading] only when nothing is already loaded** — the
         * settled refresh shape (Global Constraints): a background retry over an already-[Loaded]
         * section must not flash a spinner over rows the reader can already see.
         *
         * **[myGeneration] is captured at launch and checked before EVERY write below** — the exact
         * fix `FeedViewModel.reload`'s own KDoc documents for the identical shape: a group switch
         * landing while this suspend body is still awaiting [GroupRepository.progress]/[GroupRepository.reviews]
         * bumps [groupSectionGeneration] out from under it, and by the time this function's result
         * is ready, [groupSectionGeneration] no longer equals [myGeneration] — so the result (success
         * OR failure) is dropped rather than being written over whatever the new group has already
         * rendered.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun reloadGroupSection() {
            val currentGroupId = groupId ?: return
            if (loadingGroupSectionGeneration == groupSectionGeneration) return
            if (groupSection !is GroupSectionState.Loaded) {
                applyGroupSection(GroupSectionState.Loading)
            }
            loadingGroupSectionGeneration = groupSectionGeneration
            val myGeneration = groupSectionGeneration
            viewModelScope.launch {
                try {
                    val (progressList, reviewsList) =
                        coroutineScope {
                            // Neither call depends on the other — load()'s identical reasoning for
                            // its own media/entry pair.
                            val progressDeferred = async { groupRepository.progress(currentGroupId, mediaId) }
                            val reviewsDeferred = async { groupRepository.reviews(currentGroupId, mediaId) }
                            progressDeferred.await() to reviewsDeferred.await()
                        }
                    if (groupSectionGeneration == myGeneration) {
                        applyGroupSection(GroupSectionState.Loaded(progress = progressList, reviews = reviewsList))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    if (groupSectionGeneration == myGeneration) {
                        val stillLoaded = groupSection as? GroupSectionState.Loaded
                        applyGroupSection(stillLoaded?.copy(isStale = true) ?: GroupSectionState.Error(failure.failure))
                    }
                } finally {
                    if (loadingGroupSectionGeneration == myGeneration) loadingGroupSectionGeneration = null
                }
            }
        }

        /**
         * Writes [value] to the CANONICAL [groupSection] field first, then patches an existing
         * [DetailUiState.Success] second — see the class KDoc's own race for why the write order
         * matters: this is what lets [load]'s success branch pick up a group-section result that
         * arrived before there was any [DetailUiState.Success] to patch. [replaceSuccess]'s own
         * `.copy()` is what keeps this update from disturbing [DetailUiState.Success.saving]/
         * `.actionError`/`.proposing`/`.proposeError` — the two channels never clobber each other
         * because neither is ever rebuilt field-by-field (Global Constraints).
         */
        private fun applyGroupSection(value: GroupSectionState) {
            groupSection = value
            replaceSuccess { it.copy(groupSection = value) }
        }

        private inline fun replaceSuccess(transform: (DetailUiState.Success) -> DetailUiState.Success) {
            val latest = mutableState.value as? DetailUiState.Success ?: return
            mutableState.value = transform(latest)
        }
    }
