package com.anarky.showtrack.feature.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Review
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

private const val TAG = "ShowTrackDetail"

/** §3.6's own 1-4000 character bound, whitespace-stripped — mirrored client-side, see [ReviewSaveError]. */
private const val REVIEW_BODY_MAX_LENGTH = 4000

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
        private val authRepository: AuthRepository,
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

        // The signed-in account's own id (task 9c.7) — resolved off its own coroutine, independent
        // of [load], AuthRepository.currentUserId's own KDoc reasoning: identity is a
        // session-lifetime fact, not a screen-scoped one. Read only by [findOwnReview]; null until
        // resolved (or if resolution fails — best-effort, see [resolveCurrentUserId]'s own KDoc).
        private var currentUserId: String? = null

        // This ViewModel instance's own last-known copy of the signed-in account's review for
        // THIS title (task 9c.7, fix round 1, BLOCKING B2) — written ONLY through [cacheOwnReview],
        // from whatever the server actually returned, and read only by findOwnReview as its
        // fallback when the group section cannot answer or is not fresh enough to be trusted. See
        // findOwnReview's own KDoc for why this exists and what it does not fix (a cold start).
        private var lastOwnReview: Review? = null

        // Fix round 3, BLOCKING: the [groupSectionGeneration] a successfully-applied [GroupSectionState.Loaded]
        // was actually FETCHED under — distinct from [groupSectionGeneration] itself, which also
        // changes the instant a switch or a save is REQUESTED, before that request's own fetch has
        // resolved. Written ONLY in [reloadGroupSection]'s success branch, never on a failure: a
        // failed reload keeps the OLD data (the settled refresh shape), so its true freshness is
        // whatever this already was, not the failed attempt's own generation. [findOwnReview] reads
        // this to tell "the section already reflects the write [lastOwnReview] recorded" apart from
        // "the section still predates it" — see that function's own KDoc.
        private var groupSectionAppliedGeneration = -1

        // Fix round 3, BLOCKING: the [groupSectionGeneration] that was current at the MOMENT
        // [lastOwnReview] was last written — captured by [cacheOwnReview], called from
        // [onReviewSaved] BEFORE that function's own bump, so a [groupSectionAppliedGeneration]
        // EQUAL to this belongs to a fetch that was already in flight (or already applied) at save
        // time, not one requested because of it. Back to -1 whenever the cache is cleared: the pair
        // is written as a pair, never one without the other (fix round 4, small item 4).
        private var lastOwnReviewGeneration = -1

        init {
            load()
            resolveCurrentUserId()
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
            // justProposedToGroupId cleared HERE too, not only on success (fix round 1) — decision
            // C-S's own "clear the error before launching a retry, not only on success" rule,
            // extended to a success banner: a second propose attempt must not leave a STALE
            // confirmation for the FIRST group on screen while the second one is still in flight.
            mutableState.value = current.copy(proposing = true, proposeError = null, justProposedToGroupId = null)
            viewModelScope.launch {
                try {
                    groupRepository.proposeTitle(groupId, mediaId)
                    replaceSuccess {
                        it.copy(proposing = false, proposeError = null, justProposedToGroupId = groupId)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    replaceSuccess { it.copy(proposing = false, proposeError = failure.failure) }
                }
            }
        }

        // --- Writing and editing a review (task 9c.7, E-G, design doc §3.6) -----------------------

        /**
         * Opens the editor pre-resolved: [findOwnReview] decides up front whether this account
         * already has a review for this title (edit) or not (a fresh draft) — see
         * [ReviewEditorState]'s own KDoc for why that resolution is available synchronously here,
         * and [saveReview]'s KDoc for the fallback path when it is not (yet) available. A no-op
         * while already open — nothing here is meant to clobber an in-progress, unsaved draft.
         */
        fun openReviewEditor() {
            val current = mutableState.value as? DetailUiState.Success ?: return
            if (current.reviewEditor != ReviewEditorState.Closed) return
            val own = findOwnReview()
            mutableState.value =
                current.copy(
                    reviewEditor =
                        ReviewEditorState.Open(
                            reviewId = own?.id,
                            seedBody = own?.body.orEmpty(),
                            seedContainsSpoilers = own?.containsSpoilers ?: false,
                        ),
                )
        }

        /** Discards whatever draft is on screen, unsaved — the editor's own Cancel action. */
        fun closeReviewEditor() {
            replaceSuccess { it.copy(reviewEditor = ReviewEditorState.Closed) }
        }

        /**
         * Clears a stale [ReviewEditorState.Open.error] the moment the reader starts fixing the
         * input (fix round 1, small item 5) — decision C-S's "clear before a retry, not only on
         * success" rule, extended from "the next Save attempt" to "the next keystroke": a
         * [ReviewSaveError.BodyRequired] left on screen while the reader is visibly typing a fix is
         * stale information, not a live warning. [ReviewEditor.kt] calls this only when
         * `editor.error != null`, so a keystroke while there is nothing to clear never reaches the
         * ViewModel at all — see that file's own call sites.
         */
        fun clearReviewError() = replaceOpenReviewEditor { it.copy(error = null) }

        /**
         * Submits the editor's current draft — `POST /v1/reviews` when
         * [ReviewEditorState.Open.reviewId] is null, `PATCH /v1/reviews/{id}` when it is not. Both
         * calls always send a non-null `body`/`containsSpoilers`: the editor form always holds a
         * value for each, so there is never a field to OMIT the way a partial [edit] omits every
         * field but the one that changed — [GroupRepository.updateReview]'s "never an explicit
         * null" rule is satisfied by construction, not by tracking which field changed.
         *
         * [body] is trimmed and length-checked BEFORE any request is sent — §3.6's own 1-4000
         * character, whitespace-stripped bound, mirrored here so an out-of-range body costs no
         * round trip; the server's own `min_length=1` is checked AFTER stripping too
         * (`ReviewBody`, `backend/app/library/schemas.py`), so an all-whitespace body is exactly
         * [ReviewSaveError.BodyRequired], never a value the server would silently accept. Re-entrancy
         * guarded the same way [edit] guards `saving` — a second tap while one save is already in
         * flight is dropped. [ReviewEditorState.Open.confirmOverwrite] is cleared here too, before
         * relaunching — the confirmation is for exactly ONE tap, [confirmOverwrite]'s own KDoc.
         *
         * **The 409 path (E-G, this task's own reason to exist).** `POST /v1/reviews` answers 409
         * when the account already reviewed this title — [GroupFailure.AlreadyReviewed] — and per
         * that case's own KDoc the body never carries the existing review's id
         * ([GroupRepository]'s own KDoc confirms `backend/app/library/routes.py`'s 409 detail is a
         * fixed string). [handleSaveFailure] resolves it the same way [openReviewEditor] does — via
         * [findOwnReview] — but (fix round 1, BLOCKING B1's should-fix companion) does NOT retry
         * automatically: it switches [ReviewEditorState.Open.reviewId] to the resolved review and
         * sets [ReviewEditorState.Open.confirmOverwrite], requiring one more explicit Save tap
         * before anything is actually sent. Silently overwriting on the reader's BEHALF, the first
         * cut of this task, was wrong: a save-time resolution (the section loads only AFTER the
         * editor is already open, this function's own [openReviewEditor] never having had the
         * chance to show the existing text first) means the reader has never SEEN what they are
         * about to replace. [openReviewEditor] itself still resolves and pre-fills silently — no
         * confirmation is needed there, because the reader sees the existing text on screen BEFORE
         * choosing to type over it. Only when resolution FAILS (no active group, no loaded section,
         * and nothing yet cached in [lastOwnReview] — [findOwnReview]'s own KDoc) is there nothing
         * to switch to, and this reports [GroupFailure.AlreadyReviewed] through the ordinary
         * [ReviewSaveError.Remote] channel instead — an honest degradation, not a silent failure.
         */
        @Suppress("TooGenericExceptionCaught")
        fun saveReview(
            body: String,
            containsSpoilers: Boolean,
        ) {
            val current = mutableState.value as? DetailUiState.Success ?: return
            val editor = current.reviewEditor as? ReviewEditorState.Open ?: return
            if (editor.saving) return
            val trimmed = body.trim()
            val validation = validateReviewBody(trimmed)
            if (validation != null) {
                mutableState.value = current.copy(reviewEditor = editor.copy(error = validation))
                return
            }
            val saving = editor.copy(saving = true, error = null, confirmOverwrite = false)
            mutableState.value = current.copy(reviewEditor = saving)
            viewModelScope.launch {
                try {
                    val saved =
                        if (editor.reviewId == null) {
                            groupRepository.createReview(mediaId, trimmed, containsSpoilers)
                        } else {
                            groupRepository.updateReview(editor.reviewId, trimmed, containsSpoilers)
                        }
                    onReviewSaved(saved)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: GroupOperationException) {
                    handleSaveFailure(editor, failure)
                }
            }
        }

        private fun validateReviewBody(trimmed: String): ReviewSaveError? =
            when {
                trimmed.isEmpty() -> ReviewSaveError.BodyRequired
                trimmed.length > REVIEW_BODY_MAX_LENGTH -> ReviewSaveError.BodyTooLong
                else -> null
            }

        /**
         * [saveReview]'s own KDoc has the full reasoning for the confirm-don't-overwrite shape.
         * Named for what it handles now that update failures reach it too (fix round 1, small item
         * 6 — round 0's `handleCreateFailure` was misleading the moment [saveReview]'s `else`
         * branch, a `PATCH`, started routing its own failures through the same function): every
         * `GroupOperationException` [saveReview] catches lands here, and only a CREATE attempt that
         * 409'd ([editor]'s OWN `reviewId`, captured at the ORIGINAL request — not re-read — is
         * what that check needs) ever takes the resolve-and-confirm branch below.
         *
         * Plain, not `suspend`: unlike round 0, this performs no network call of its own any more —
         * [findOwnReview] is synchronous, and the actual retry is now the reader's own next
         * [saveReview] call, not something this function does on their behalf.
         *
         * [replaceOpenReviewEditor] (fix round 1, small item 1), not a `.copy()` of the CAPTURED
         * [editor] parameter: this runs after the one suspend point in [saveReview] (the
         * create/update call itself), so [editor] may be stale — Cancel is disabled while saving so
         * nothing reaches this today, but a future programmatic close (deep link, nav-away) must
         * not have its [ReviewEditorState.Closed] silently overwritten by a snapshot from before it
         * happened. [replaceOpenReviewEditor] re-reads the CURRENT `reviewEditor` and is a deliberate
         * no-op when it is no longer [ReviewEditorState.Open].
         */
        private fun handleSaveFailure(
            editor: ReviewEditorState.Open,
            failure: GroupOperationException,
        ) {
            val ownReview =
                if (editor.reviewId == null && failure.failure is GroupFailure.AlreadyReviewed) {
                    findOwnReview()
                } else {
                    null
                }
            if (ownReview == null) {
                // Fix round 2, small item 2: a PATCH 404 (NoSuchEntry) on the id this ViewModel
                // itself cached means the CACHE is the thing that is wrong — the review it points
                // at is gone server-side. Left alone, [lastOwnReview] would keep resolving future
                // opens to the SAME dead id, reproducing the identical 404 for the life of this
                // instance; a delete is client-unreachable today (no `GroupRepository.deleteReview`
                // exists), so this is a defensive self-heal for whenever that changes, not a path
                // any current UI action can trigger. Scoped to the id that actually failed —
                // [editor.reviewId] — so a 404 on some OTHER resolved id never clears a still-good
                // cache entry for a different review.
                if (failure.failure is GroupFailure.NoSuchEntry && lastOwnReview?.id == editor.reviewId) {
                    cacheOwnReview(null)
                }
                replaceOpenReviewEditor { it.copy(saving = false, error = ReviewSaveError.Remote(failure.failure)) }
                return
            }
            replaceOpenReviewEditor {
                it.copy(reviewId = ownReview.id, saving = false, error = null, confirmOverwrite = true)
            }
        }

        /**
         * Common success path for both a create and an edit. [saved] is cached into [lastOwnReview]
         * (fix round 1, BLOCKING B2) — the ONE time this session will ever hold this account's own
         * review id/body/spoiler-flag directly from the server without depending on a group section
         * to have fetched it — before the editor closes and the group section (task 9c.6's own
         * reload path) is refreshed so the ACTIVE group's reviews list picks up what the server now
         * has. [saved] is otherwise NOT written into [groupSection] itself: the same "the state the
         * server hands back, not the request" discipline [edit] already follows for a library entry
         * (this class's own KDoc) — the reload above is what keeps [groupSection] itself honest, not
         * an optimistic insert of [saved] into it. A no-op reload when there is no active group —
         * nothing in [groupSection] needs refreshing if it was never scoped to one.
         *
         * [cacheOwnReview] stamps [lastOwnReviewGeneration] BEFORE the bump below (fix round 3,
         * BLOCKING) — see [findOwnReview]'s own KDoc for what that ordering buys: a
         * [groupSectionAppliedGeneration] EQUAL to this value means the currently-shown section was
         * fetched no later than THIS save, so it must never be trusted over [lastOwnReview] —
         * covers a post-save reload that FAILS (the section then keeps its pre-write data, marked
         * stale, until the next SUCCESSFUL refresh, which only [retryGroupSection] or a resume can
         * produce) as well as one still in flight, neither of which the generation bump alone (fix
         * round 2) touches: that bump only ever decided which FETCH's RESULT gets WRITTEN, never
         * how trustworthy an already-written result still is once time has passed.
         */
        private fun onReviewSaved(saved: Review) {
            cacheOwnReview(saved)
            replaceSuccess { it.copy(reviewEditor = ReviewEditorState.Closed) }
            if (groupId != null) {
                // Fix round 2, SHOULD-FIX: bumping the generation FIRST is what makes an EARLIER,
                // still in-flight progress/reviews fetch (launched before this save resolved) get
                // its eventual result DISCARDED instead of overwriting the section with pre-write
                // data — [reloadGroupSection]'s own generation check
                // (`groupSectionGeneration == myGeneration`) already exists for exactly this shape,
                // [setActiveGroup]'s identical mechanism for a group switch; this reuses it for a
                // SECOND trigger (a write racing a still-running read for the SAME group), not only
                // a switch. Without the bump, [reloadGroupSection]'s re-entrancy guard
                // (`loadingGroupSectionGeneration == groupSectionGeneration`) silently DROPS this
                // call whenever a resume-triggered fetch is still in flight — the older fetch then
                // lands, and (WITHOUT [lastOwnReviewGeneration]'s own freshness check above) would
                // get trusted outright over a genuinely fresher [lastOwnReview]. Bumping first forces
                // the guard open for a fresh fetch, and makes the OLDER fetch's own landing a no-op
                // via the SAME check — the two fixes are complementary, not redundant: this bump
                // decides which fetch's result gets APPLIED; [lastOwnReviewGeneration] decides
                // whether an ALREADY-APPLIED result is still trustworthy once this save has happened.
                groupSectionGeneration++
                reloadGroupSection()
            }
        }

        /**
         * The ONE write path for [lastOwnReview] and [lastOwnReviewGeneration] (fix round 4, small
         * item 4). The two are a pair — a cached review and the [groupSectionGeneration] it was
         * written under — and the only way to keep them coherent is to give them no separate write
         * sites to drift between: round 3's [handleSaveFailure] cleared the review and left the
         * generation at its stale-high value, which happened to be harmless (a null cache falls
         * through to the section's own answer either way, [findOwnReview]) but was one edit away
         * from not being. Clearing resets the generation to the same `-1` the field starts at, so
         * "no cached review" and "never wrote one" are the same state, not two.
         */
        private fun cacheOwnReview(review: Review?) {
            lastOwnReview = review
            lastOwnReviewGeneration = if (review == null) -1 else groupSectionGeneration
        }

        /**
         * Matches the signed-in account's own id ([currentUserId], resolved independently — see
         * [resolveCurrentUserId]'s own KDoc) against whatever [GroupSectionState.Loaded.reviews]
         * the ACTIVE group's section already has loaded. Reviews of a title are visible to every
         * group the author is a member of (`list_group_reviews`, `backend/app/groups/service.py`),
         * so a reviewer looking at this screen with an active, loaded group section is necessarily
         * looking at a list that already includes their own review, if one exists — PROVIDED that
         * section came from a fetch requested AFTER the account's own last write. That proviso is
         * new in fix round 3 and governs the whole of this function as of fix round 4; see below
         * for why round 1's original "trust it outright" claim did not hold, and why round 3's own
         * half-application of the proviso — the no-match branch only — did not either.
         *
         * **[lastOwnReview] is the fallback when the section itself is not [GroupSectionState.Loaded]**
         * (fix round 1, BLOCKING B2) — `Absent`/`Loading`/`Error`, or [currentUserId] has not
         * resolved yet. Round 0 returned null unconditionally in that case, which permanently locked
         * a no-groups account out of ever editing a review it had just written IN THIS SAME SESSION:
         * write once (create succeeds, [onReviewSaved] caches [lastOwnReview]) → close the editor →
         * open it again → [groupSection] is still [GroupSectionState.Absent] (no active group ever
         * existed to fetch one from) → resolution failed → a fresh `POST` → 409, forever, on a
         * review whose id this ViewModel instance already knew. [lastOwnReview] closes that hole for
         * the life of this screen; it does NOT survive a cold start (a fresh [DetailViewModel]
         * instance, e.g. after leaving and reopening this title) — that case genuinely needs a
         * server-side "my own review" lookup this phase's API surface does not have, out of scope
         * here (recorded as a follow-up, not silently dropped).
         *
         * **When the section IS [GroupSectionState.Loaded] and [currentUserId] IS known, its answer
         * — WHATEVER that answer is — is trusted only if [groupSectionAppliedGeneration] is STRICTLY
         * GREATER than [lastOwnReviewGeneration]** (fix round 3, BLOCKING; extended to the MATCH
         * branch in fix round 4, BLOCKING). The two generations answer exactly one question — did
         * the data CURRENTLY on screen come from a fetch that started no earlier than this save —
         * and that question does not care whether the stale list happens to contain a row for this
         * account. Round 3 gated only the NO-match branch, which left the mirror-image case open: a
         * section holding the PRE-edit copy of the very review that was just edited would win over
         * a strictly fresher [lastOwnReview], so reopening the editor after `PATCH` succeeded but
         * its post-save reload failed re-seeded the form with the OLD body and the OLD spoiler flag
         * — and, since `reviewId` was nonetheless correct, one Save tap then wrote that old text
         * back to the server, silently reverting a successful edit and un-hiding a spoiler the
         * reader had just marked. One rule for both branches, not two.
         *
         * Fix round 2's generation BUMP does not answer this question on its own: that bump only
         * ever decided which fetch's RESULT gets WRITTEN into [groupSection], never how trustworthy
         * an already-written result still is once a save has since happened without a corresponding
         * successful refresh. Nor is the stale answer trusted forever — `isStale` data is refreshed
         * by [retryGroupSection] (the section's own Retry button, wired in `DetailScreen.kt`) and by
         * every resume that reaches [setActiveGroup] — but "until the next SUCCESSFUL refresh" is
         * still unbounded from the reader's side, and an edit silently reverted inside that window
         * is not recoverable by refreshing afterward.
         *
         * The invariant that makes the comparison meaningful: whenever [groupSection] is
         * [GroupSectionState.Loaded], [groupSectionAppliedGeneration] names the generation of the
         * fetch that produced its rows. [setActiveGroup] blanks unconditionally on a switch, so a
         * `Loaded` can only ever come from [reloadGroupSection]'s success branch (which stamps) or
         * from its failure branch re-publishing that same payload with `isStale` (which
         * deliberately does not).
         */
        private fun findOwnReview(): Review? {
            val loaded = groupSection as? GroupSectionState.Loaded ?: return lastOwnReview
            val userId = currentUserId ?: return lastOwnReview
            val sectionReflectsLastWrite = groupSectionAppliedGeneration > lastOwnReviewGeneration
            if (!sectionReflectsLastWrite && lastOwnReview != null) return lastOwnReview
            return loaded.reviews.firstOrNull { review -> review.author.id == userId }
        }

        /**
         * [handleSaveFailure]'s own KDoc explains why a fresh read matters here: [transform] is
         * applied to whatever [ReviewEditorState.Open] is CURRENT at the moment this runs, not to a
         * value captured before a suspend point — and this is a deliberate no-op, leaving
         * [DetailUiState.Success] otherwise untouched, when `reviewEditor` is no longer
         * [ReviewEditorState.Open] by the time it runs.
         */
        private inline fun replaceOpenReviewEditor(transform: (ReviewEditorState.Open) -> ReviewEditorState.Open) {
            replaceSuccess { success ->
                val current = success.reviewEditor as? ReviewEditorState.Open
                if (current == null) success else success.copy(reviewEditor = transform(current))
            }
        }

        /**
         * Resolved independently of [load] — `GroupDetailViewModel.loadCurrentUserId`'s identical
         * shape one screen over, see [AuthRepository.currentUserId]'s own KDoc for why identity is
         * a session-lifetime fact fetched off its own coroutine rather than folded into a
         * screen-scoped load. Best-effort and un-retried: a failure here only degrades
         * [findOwnReview] to "cannot resolve locally", which [saveReview]'s own 409 path already
         * treats as a real, handled outcome — not a reason to retry a fetch nothing else on this
         * screen is blocked on.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun resolveCurrentUserId() {
            viewModelScope.launch {
                try {
                    currentUserId = authRepository.currentUserId()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val name = failure.javaClass.simpleName
                    Log.w(TAG, "could not resolve the signed-in user's id for the review editor: $name")
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun load() {
            // Captured BEFORE the blank below, not after: this is what [previous] means in the
            // success branch — whatever Success existed at the moment load() was CALLED, not at
            // the moment it finished (by then mutableState is always Loading, this function's own
            // next line). Safe today either way — load() is reachable only from init (nothing
            // exists yet) and retry() (the screen wires that button only under the Error branch,
            // so there is never a Success to capture) — but a plain, capture-nothing rebuild here
            // is the exact bug class this phase has now hit three times (fix round 2, coordinator
            // finding 5): a Success gains an eighth field later, this line is not the one anyone
            // remembers to update, and every field load() itself does not touch — [DetailUiState.Success.proposing]/
            // `.proposeError`/`.justProposedToGroupId`, [DetailActionError] — silently resets to
            // its default the next time load() runs, WHENEVER that becomes reachable with a
            // Success already on screen. `.copy()` over [previous] makes that safe by
            // construction instead of by a fact every future field addition has to remember.
            val previous = mutableState.value as? DetailUiState.Success
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
                    // patch it into yet. [previous]'s own `.copy()` is what carries every OTHER
                    // field (saving/actionError/proposing/proposeError/justProposedToGroupId)
                    // forward unchanged when there is a Success to carry them FROM — see this
                    // function's own KDoc for why that case is unreachable today but not free to
                    // get wrong.
                    mutableState.value =
                        previous?.copy(data = data, groupSection = groupSection)
                            ?: DetailUiState.Success(data = data, groupSection = groupSection)
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
         *
         * **The success branch alone stamps [groupSectionAppliedGeneration]** (fix round 3,
         * BLOCKING) — a FAILURE keeps the OLD data on screen (marked stale), so it must NOT claim
         * that data is as fresh as this attempt; only a genuinely NEW, successfully-applied
         * [GroupSectionState.Loaded] advances what [findOwnReview] is willing to trust.
         */
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
                        groupSectionAppliedGeneration = myGeneration
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
