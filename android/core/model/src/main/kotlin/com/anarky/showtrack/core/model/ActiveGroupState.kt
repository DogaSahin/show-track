package com.anarky.showtrack.core.model

/**
 * What `:app`'s `ActiveGroupViewModel` (task 9c.5) knows about the signed-in account's groups and
 * which one is active. Lives here, not in `:app`, because `:feature:feed`/`:feature:groups` both
 * consume it as a route-argument-shaped value (decision E-C) — the same reason [Group]/
 * [GroupFailure] live here rather than in `:core:data`: a type that crosses the `:app`/`:feature:*`
 * boundary has to live somewhere both sides already depend on, and `:feature:*` modules cannot
 * depend on `:app` (architecture rule 1's own shape, one level up).
 *
 * [Loading]/[Error] are distinct from `Success(groups = emptyList(), activeGroupId = null)` on
 * purpose (fix round 1, BLOCKING B3): the first two are "we don't know yet" and "the fetch failed";
 * the third is "we asked, and this account genuinely has zero groups". Collapsing all three into
 * one — the shape this task shipped with before this fix — makes Feed's create-or-join invitation
 * (E-K) render during a load and after a failure too, which is not an empty state, it is a wrong
 * answer with confident copy.
 */
sealed interface ActiveGroupState {
    data object Loading : ActiveGroupState

    data class Success(
        val groups: List<Group>,
        val activeGroupId: String?,
    ) : ActiveGroupState

    data class Error(
        val cause: GroupFailure,
    ) : ActiveGroupState
}
