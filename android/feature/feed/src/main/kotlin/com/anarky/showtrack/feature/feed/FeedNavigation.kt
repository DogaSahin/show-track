package com.anarky.showtrack.feature.feed

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * [activeGroup] threads straight through to [FeedScreen] with no translation — Ruling 1
 * (`progress.md`): `FeedRoute` itself carries no argument (it is a top-level tab, reached from the
 * nav bar, not from a specific group's row), so the active group has to arrive from OUTSIDE the
 * graph. `AppDestination.kt` is the caller that supplies it (task 9c.5, from `ActiveGroupViewModel`
 * in `:app`), the same split `GroupDetailRoute` draws for its OWN argument except one level further
 * out, since this route has none of its own to carry it in.
 *
 * **`StateFlow`, not a plain value (task 9c.5).** `AppDestination.kt`'s own KDoc explains why: the
 * graph's `builder` lambda that calls this function is only re-invoked when `NavHost`'s own
 * `remember` keys change, which for a signed-in session is effectively never again — a plain value
 * captured here would freeze at whatever it read the one time this function ran. [FeedScreen]'s
 * stateful overload collects it reactively, inside its OWN composition, which recomposes on every
 * emission regardless of how rarely this registration itself re-runs.
 *
 * [onRetryGroups] (fix round 1, BLOCKING B3) is a SEPARATE channel from `onRetry`
 * (`FeedViewModel.refresh`, `FeedScreen`'s own stateful overload) — decision C-S: retrying the
 * GROUPS fetch (`ActiveGroupViewModel.refresh`, an `:app`-owned operation this module has no other
 * way to reach) and retrying the FEED fetch are two different operations behind two different
 * failures, and conflating them would retry the wrong one.
 *
 * `onNavigate` forwards untouched, same as before this task: `FeedScreen` builds its own
 * `DetailRoute`/`GroupsRoute` navigation and hands it to [onNavigate] without ever naming
 * `:feature:detail`/`:feature:groups` — this module's build file names only `:core:navigation` and
 * `:core:data`. That this compiles, together with Task 1's TestKit test that fails the build the
 * moment a `:feature:*` module depends on another `:feature:*` module, is architecture rule 1
 * demonstrated by construction rather than asserted by a test that could never fail.
 */
fun NavGraphBuilder.feedEntry(
    activeGroup: StateFlow<ActiveGroupState>,
    onSwitchGroup: (String) -> Unit,
    onRetryGroups: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    composable<FeedRoute> {
        FeedScreen(
            activeGroup = activeGroup,
            onSwitchGroup = onSwitchGroup,
            onRetryGroups = onRetryGroups,
            onNavigate = onNavigate,
        )
    }
}
