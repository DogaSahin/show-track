package com.anarky.showtrack.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * `onNavigate: (Any) -> Unit` rather than a `(AppRoute) -> Unit`: this is the shape Task 9's
 * `NavHost` will hand every feature's entry point (`navController::navigate` itself takes `Any`),
 * so this screen is written against the exact signature it will be composed into, not a
 * placeholder.
 */
@Composable
fun FeedScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
) {
    Text(
        text = "Feed",
        modifier = modifier.clickable { onNavigate(DetailRoute(mediaId = "placeholder")) },
    )
}
