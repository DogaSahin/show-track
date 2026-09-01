package com.anarky.showtrack.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute

/**
 * Tapping a feed entry jumps straight to another feature's screen: `FeedScreen` builds a
 * `DetailRoute` and hands it to [onNavigate] without ever naming `:feature:detail` — this
 * module's build file names only `:core:navigation`. That this compiles, together with Task 1's
 * TestKit test that fails the build the moment a `:feature:*` module depends on another
 * `:feature:*` module, is architecture rule 1 demonstrated by construction rather than asserted
 * by a test that could never fail (there is no build-file text to assert against once the real
 * guard already lives in the convention plugin).
 *
 * `onNavigate: (AppRoute) -> Unit`, not `(Any) -> Unit`: the entire point of `:core:navigation`
 * is that `DetailRoute("abc")` is compiler-checked where a string route is not, and `(Any) ->
 * Unit` would accept `onNavigate("detail/abc")` or `onNavigate(42)` with no diagnostic — the
 * string route back, one module up from where it was removed. This composes into Task 9's
 * `NavHost` unchanged: `NavController.navigate(route: Any)` accepts an `AppRoute` because
 * `AppRoute : Any`, so `onNavigate = navController::navigate` still type-checks. No default
 * value, deliberately: a navigation callback defaulted to a no-op would let a screen get wired
 * into the graph without one and silently do nothing on tap; a missing-argument compile error at
 * the `NavGraphBuilder` call site is worth more than the convenience.
 */
@Composable
fun FeedScreen(
    modifier: Modifier = Modifier,
    onNavigate: (AppRoute) -> Unit,
) {
    Text(
        text = "Feed",
        modifier = modifier.clickable { onNavigate(DetailRoute(mediaId = "placeholder")) },
    )
}
