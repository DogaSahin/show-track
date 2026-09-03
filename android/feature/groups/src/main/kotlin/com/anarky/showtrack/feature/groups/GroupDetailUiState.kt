package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.WatchlistEntry

/**
 * The group DETAIL screen's state (task 9c.2). [GroupsUiState]'s identical shape and identical
 * reasoning — a closed sealed hierarchy, not a bag of booleans — applied one screen over: a `when`
 * over this cannot represent "loading AND showing an error AND holding a stale member list" all at
 * once, and [GroupsUiState]'s own KDoc documents the two-round bug (a single `Success`-cast guard
 * serving both "have we ever loaded" and "is an action in flight") that a bag of booleans produced
 * the first time this project tried that shape. [GroupDetailActionState] exists for the identical
 * reason [GroupsActionState] does — see its own KDoc.
 *
 * Task 9c.3 extends [Success] with the shared watchlist (`GroupDetailUiState.kt`'s own file is
 * where the plan says that extension lands) — nothing here is written to preclude it.
 */
sealed interface GroupDetailUiState {
    /** The initial load, or a retry from [Error], is in flight. Replaces whatever was on screen. */
    data object Loading : GroupDetailUiState

    /**
     * [currentUserId] deliberately does NOT live here (round 1 review, BLOCKING 2/3 and the
     * ruling that resolved them). Round 0 fetched it inside the SAME `try` as [members], which
     * meant a failed member-list load left no id in existence at all, and every action needing
     * it — most of all leaving the group — died silently with the load, even though leaving has
     * nothing to do with whether the member list loaded. Identity is now a session-lifetime fact
     * `GroupDetailViewModel` resolves independently via `AuthRepository.currentUserId` and holds
     * in its OWN field — see that method's own KDoc — passed to [GroupDetailScreen] as a sibling
     * parameter to this state, not folded into it. Owner-only rendering (E-F) is still derived
     * fresh at render time from the two values TOGETHER — "is this member me, and am I the
     * owner" is cheap to recompute on every render — E-F's stated mitigation ("derived from the
     * live members response... not from anything cached or inferred") is still literally true;
     * only WHERE the id itself is held changed.
     *
     * [rotatedInvite] mirrors [GroupsUiState.Success.justCreated] (decision E-I, restated for this
     * screen's own rotate action): the invite code returned by `GroupRepository.rotateInvite` is
     * shown ONCE, held only in this in-memory state, and is dropped by the very next
     * [GroupDetailViewModel.refresh] — the identical discipline [GroupsViewModel.refresh]'s own
     * KDoc documents for `justCreated`, applied identically here: E-I's own position is that a
     * member who needs the code again rotates it.
     *
     * **NOT dropped by [GroupDetailViewModel.removeMember]'s own reload** (round 1 review, minor
     * 5 — a fix, not the original design): round 0 had ONE reload path that always defaulted this
     * to `null`, which meant an owner rotating the code and then removing a different member lost
     * the just-rotated code from screen with no way back except rotating again — invalidating a
     * code they may already have sent. Removing a member has nothing to do with the invite banner;
     * `GroupDetailViewModel.reloadMembers`'s `preserveRotatedInvite` parameter is what lets the two
     * callers disagree correctly.
     *
     * [isStale] mirrors [GroupsUiState.Success.isStale] — the settled refresh shape (Global
     * Constraints): a failed background [GroupDetailViewModel.refresh] over an already-populated
     * screen marks the existing [members] stale rather than blanking or error-ing them away, and the
     * next successful refresh clears it.
     *
     * **Task 9c.3's own addition:** [watchlist]/[watchlistLoadingMore]/[watchlistPageError], folded
     * into this SAME `Success` rather than a second sealed hierarchy the way [rotatedInvite] is a
     * plain field rather than a second state machine. Deliberately NOT independent of [members] the
     * way [GroupDetailViewModel.currentUserId] is — that field lives outside this type because it is
     * a SESSION fact several unrelated actions need regardless of what this screen shows
     * ([GroupDetailViewModel]'s own KDoc); the watchlist is screen CONTENT, exactly the category
     * [members] already is, and [GroupDetailUiState] exists precisely so a `when` over it cannot
     * represent an impossible combination — a second top-level branch per section would only
     * multiply that combinatorial space, not shrink it.
     *
     * What this does NOT mean: a failed watchlist fetch never promotes this whole screen to
     * [Error] — see [GroupDetailViewModel.reloadWatchlist]'s own KDoc for why every watchlist
     * reload failure, first page included, surfaces as [watchlistIsStale] rather than replacing
     * [members] on screen. That is the direct answer to this task's own "can a user still act on
     * what they can see" question: [members] and [watchlist] fail independently, so a broken
     * watchlist fetch can never take a correctly-loaded member list off screen, and a stale/broken
     * member list never hides watchlist rows that DID load.
     *
     * **Fix round 1 split what round 0 folded into one field.** Round 0 had [watchlistPageError]
     * answer two different questions — "did the last RELOAD fail" and "did the last `loadMore` page
     * fetch fail" — the SAME shape decision C-S already names and rejects, and this is the shape's
     * seventh occurrence in this project. The two failures need different recoveries: a `loadMore`
     * retry only makes sense if there IS a next page to fetch, but a reload can fail on an EXHAUSTED
     * single-page list, where a footer wired to `loadMoreWatchlist` alone is a dead tap forever
     * (fix round 1, blocking finding B2). [watchlistPageError] is now `loadMoreWatchlist`'s own
     * channel exclusively — a footer under otherwise-valid rows, `LibraryUiState.Success.pageError`'s
     * identical shape in `:feature:library` (plain text, not a doc link — `:feature:groups` cannot
     * depend on that module, architecture rule 1). [watchlistIsStale] is `reloadWatchlist`'s own
     * channel exclusively, [isStale]'s identical shape one section down: a failed reload keeps
     * whatever rows are already known and marks them stale rather than blanking or erroring them
     * away, retried through `refresh()` — the SAME retry [isStale]'s own banner already uses, not a
     * second bespoke function. This split is also what makes fix round 1's B3 finding resolve for
     * free: `removeFromWatchlist`'s reload failing after a genuinely successful delete now marks the
     * section stale (the deleted row may still show, honestly labelled as possibly outdated) rather
     * than silently claiming success while lying about freshness.
     */
    data class Success(
        val members: List<GroupMember>,
        val watchlist: List<WatchlistEntry> = emptyList(),
        val watchlistLoadingMore: Boolean = false,
        val watchlistPageError: GroupFailure? = null,
        val watchlistIsStale: Boolean = false,
        val rotatedInvite: GroupWithInvite? = null,
        val isStale: Boolean = false,
    ) : GroupDetailUiState

    /** Only a failed [GroupDetailViewModel.refresh] with nothing already on screen ever produces this. */
    data class Error(
        val cause: GroupFailure,
    ) : GroupDetailUiState
}

/**
 * The three owner/member actions' own state, independent of [GroupDetailUiState] — [GroupsActionState]'s
 * own KDoc explains why a second, independent type exists at all rather than folding these fields
 * onto [GroupDetailUiState.Success]: the SAME bug that KDoc documents (a `Success`-cast guard
 * serving both "have we loaded" and "is an action in flight", which left Create/Join permanently
 * dead from [GroupsUiState.Error]) is exactly as reachable here — a member holding a valid reason to
 * leave a group has every reason to do so from a screen whose OWN member-list load just failed
 * ([GroupDetailUiState.Error] is not transient, the identical note [GroupsUiState.Error] carries).
 *
 * THREE separate channels (decision C-S: one error channel per operation, not one per screen), not
 * one shared "actionError": [rotating]/[rotateError] for the owner's rotate action,
 * [removingUserId]/[removeError] for the owner removing a DIFFERENT member, and [leaving]/[leaveError]
 * for any member leaving. Rotate and remove are already distinct per decision C-S's own logic
 * (different endpoints). Leave and remove are the SAME endpoint (`DELETE /members/{userId}`, design
 * doc §1.1) called with a different id, but kept on separate channels anyway: they have different
 * copy, different confirmation dialogs, and different success handling (leave navigates away; remove
 * refreshes the member list in place) — a guard belongs where the caller is known (Global
 * Constraints), and "which of the two this call site meant" is exactly a fact only the CALLER
 * (leave button vs. a specific row's remove button) has, not something a single shared flag could
 * reconstruct afterward. Sharing one flag would also make the owner's OWN row show "removing…" while
 * they tap Leave, and vice versa, for two actions that are conceptually unrelated to their caller
 * even though they land on the identical repository call underneath.
 *
 * [removingUserId] (rather than a bare `removing: Boolean`) names WHICH row's remove is in flight —
 * needed because [GroupDetailScreen] renders one remove control PER OTHER member, and a bare
 * boolean could not tell "disable every row's remove control" apart from "disable only the one
 * being removed", which is the wrong UX (racing two different members' removals is a real,
 * supportable case; the endpoint has no cross-member conflict to guard against, only re-entrancy on
 * the SAME id — enforced below by rejecting a second [GroupDetailViewModel.removeMember] while
 * [removingUserId] is already non-null, mirroring [GroupsActionState]'s re-entrancy guards).
 *
 * **Task 9c.3 adds one more channel, the identical decision C-S reasoning extended to the
 * watchlist:** [removingEntryId]/[removeEntryError] for [GroupDetailViewModel.removeFromWatchlist].
 * [removingEntryId] is [removingUserId]'s identical shape — one entry's id, not a bare boolean, for
 * the identical reason: [GroupDetailScreen] renders one remove control per watchlist row. Not
 * merged with [removingUserId]/[removeError]: those are `DELETE .../members/{userId}` — a different
 * endpoint, a different resource, a different confirmation dialog — sharing a channel would make
 * removing a MEMBER and removing a WATCHLIST ENTRY block each other for no reason either one's
 * caller would expect.
 *
 * **Fix round 1 removed `proposing`/`proposeError`.** Proposing a title needs a real title picker,
 * which needs a persisted `mediaId` a search result does not carry (decision C-N) — the ruling that
 * resolved this moved "propose to a group" to `:feature:detail` (task 9c.6), where a real `mediaId`
 * already exists. Shipping a raw-media-id text field here was worse than not offering the action
 * yet.
 */
data class GroupDetailActionState(
    val rotating: Boolean = false,
    val rotateError: GroupFailure? = null,
    val removingUserId: String? = null,
    val removeError: GroupFailure? = null,
    val leaving: Boolean = false,
    val leaveError: GroupFailure? = null,
    val removingEntryId: String? = null,
    val removeEntryError: GroupFailure? = null,
)
