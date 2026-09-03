package com.anarky.showtrack.feature.feed

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.FeedRoute

/**
 * [activeGroupId] threads straight through to [FeedScreen] with no translation — Ruling 1
 * (`progress.md`): `FeedRoute` itself carries no argument (it is a top-level tab, reached from the
 * nav bar, not from a specific group's row), so the active group has to arrive from OUTSIDE the
 * graph. `AppDestination.kt` is the caller that supplies it (`null` today; the real value once
 * task 9c.5 hoists an `ActiveGroupViewModel` in `ShowTrackApp`), the same split `GroupDetailRoute`
 * draws for its OWN argument except one level further out, since this route has none of its own to
 * carry it in.
 *
 * `onNavigate` forwards untouched, same as before this task: `FeedScreen` builds its own
 * `DetailRoute` and hands it to [onNavigate] without ever naming `:feature:detail` — this module's
 * build file names only `:core:navigation` and `:core:data`. That this compiles, together with
 * Task 1's TestKit test that fails the build the moment a `:feature:*` module depends on another
 * `:feature:*` module, is architecture rule 1 demonstrated by construction rather than asserted by
 * a test that could never fail.
 */
fun NavGraphBuilder.feedEntry(
    activeGroupId: String?,
    onNavigate: (AppRoute) -> Unit,
) {
    composable<FeedRoute> {
        FeedScreen(activeGroupId = activeGroupId, onNavigate = onNavigate)
    }
}
